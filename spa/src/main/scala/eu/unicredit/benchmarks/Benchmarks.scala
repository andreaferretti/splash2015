package eu.unicredit.benchmarks

import scala.scalajs.js
import scala.concurrent.duration._
import scala.collection.mutable.Buffer
import js.Dynamic.literal
import js.JSConverters._
import js.annotation.JSExport

import akka.actor._

import eu.unicredit.ws._
import eu.unicredit.colors._

import paths.high.Stock

class BenchmarkPage extends VueActor {

  val vueTemplate =
    """
      <div class="col-md-12">
        <h1>
          Benchmarks
          <small>
            <span class="glyphicon glyphicon-dashboard"></span>
          </small>
        </h1>
      </div>
    """

  def operational = {
    val bench = context.actorOf(Props(new BenchmarkRunner("Are we fast yet?")), "benchmark")
    vueBehaviour
  }
}

case class Result(
  param: Int,
  time: Double
)

class BenchmarkRunner(title: String) extends VueActor {
  val vueTemplate = s"""
    <div class="row">
      <h4>$title</h4>
    </div>
  """

  implicit val dispatcher = context.dispatcher

  def operational =  {
    //val button = context.actorOf(Props(new RunButton()))
    val bbox = context.actorOf(Props(new BenchmarkBox("pipe")))

    vueBehaviour orElse {
      case _ =>
      /*case StartBenchmark =>
        button ! PoisonPill
        val graph = context.actorOf(Props(new BenchmarkBox("pipe")))
        //for (i <- 1 to 15) {
          //context.system.scheduler.scheduleOnce(i.seconds, graph, Result(i, i * i))
        //}
      */
    }
  }
}

case object StartPage
case object StartNode
case object StartJvm

@JSExport
case class GraphResult(
  param: Int,
  color: Color,
  time: Int
) {
  @JSExport def light = string(color.copy(alpha = 0.6))
  @JSExport def dark = string(color)
}

class BenchmarkBox(name: String) extends VueActor {
  println("Starting bbox ")

  val vueTemplate = s"""
    <div class="col-md-12">
      <div class="row">
        <h2>$name</h2>
      </div>
      <div class="row">
        <div class="btn-group">
          <button type="button" class="btn btn-primary" v-on='click:startPage("${name}")'>Run in page</button>
          <button type="button" class="btn btn-primary" v-on='click:startNode("${name}")'>Run on Node</button>
          <button type="button" class="btn btn-primary" v-on='click:startJvm("${name}")'>Run on Jvm</button>
        </div>
      </div>
    </div>
  """

  override val vueMethods = literal(
    startPage = (n: String) => if (n == name) self ! StartPage,
    startNode = (n: String) => if (n == name) self ! StartNode,
    startJvm = (n: String) => if (n == name) self ! StartJvm)

  def operational = {
    val graph = context.actorOf(Props(new Benchmark()))

    vueBehaviour orElse {
      case StartPage =>
        println("start page")
        context.actorOf(Props(new PageBench(graph)))
      case StartNode =>
        println("start node")
        context.actorOf(Props(new NodeBench(graph)))
      case StartJvm =>
        println("start jvm")
        context.actorOf(Props(new JvmBench(graph)))
      case any =>
        println("Received "+any)
    }
  }

  trait BenchReceiver {
    self: Actor =>
    val graph: ActorRef

    val color: Color

    var n: Int = 0

    def receive: Receive = {
      case BenchResult(name, time) =>
        graph ! GraphResult(n, color, time.toInt)
        n += 1
        println("run and result is "+time)
    }
  }

  trait WSBenchReceiver {
    self: Actor =>

    val ip: String

    import org.scalajs.dom.raw._

    lazy val ws = new WebSocket(ip)
    override def preStart() = {
        ws.onmessage = { (event: MessageEvent) =>
          println("received!! "+event.data.toString)
          val splitted = event.data.toString.split(",")
          val from = splitted(0)
          val txt = splitted(1)

          self ! BenchResult(from, txt)
        }
        ws.onopen = { (event: Event) =>
        ws.send(s"benchmark,$name")
      }
    }

    override def postStop() = {
      ws.close()
    }
  }

  class PageBench(val graph: ActorRef) extends Actor with BenchReceiver {
    val color = Color(158, 199, 247)
    context.actorOf(Props(new BenchActor(name)))
  }

  class NodeBench(val graph: ActorRef) extends Actor with BenchReceiver with WSBenchReceiver {
    val color = Color(183, 188, 192)
    val ip = "ws://localhost:9090"
  }

  class JvmBench(val graph: ActorRef) extends Actor with BenchReceiver with WSBenchReceiver {
    val color = Color(120, 129, 194)
    val ip = "ws://localhost:9000"
  }
}

class Benchmark extends VueActor {
  var results = Map.empty[Color, Buffer[GraphResult]]

  val vueTemplate = """
      <svg width="600" height="420">
        <g transform="translate(80, 40)">
          <line x1="{{xmin - 10}}" y1="{{ymin}}" x2="{{xmax + 10}}" y2="{{ymin}}" stroke="#cccccc" />
          <line x1="{{xmin}}" y1="{{ymin + 10}}" x2="{{xmin}}" y2="{{ymax - 10}}" stroke="#cccccc" />
          <line v-repeat="vdots" x1="{{xmin}}" y1="{{y}}" x2="{{xmax}}" y2="{{y}}" stroke="#eeeeee" />

          <g v-repeat="curves">
            <path d="{{area.path.print()}}" fill="{{item[0].light}}" stroke="none" />
            <path d="{{line.path.print()}}" fill="none" stroke="{{item[0].dark}}" />
          </g>
          <g v-repeat="dots" transform="translate({{x}}, {{y}})">
            <circle r="2" x="0" y="0" stroke="#cccccc" fill="white" />
            <text transform="translate(-3, 15)">{{param}}</text>
          </g>
          <g v-repeat="vdots" transform="translate({{x}}, {{y}})">
            <circle r="2" x="0" y="0" stroke="#cccccc" fill="white" />
            <text transform="translate(-20, 0)">{{time}}</text>
          </g>
        </g>
      </svg>
    """

  def operational =
    vueBehaviour orElse {
      case r: GraphResult =>
        if (results.get(r.color).isEmpty) {
          results += (r.color -> Buffer())
        }
        results(r.color) += r
        val allResults = results.values.flatten.toSeq
        if (allResults.length > 1) {
          val stock = Stock[GraphResult](
              data = results.values.toSeq,
              xaccessor = _.param,
              yaccessor = _.time,
              width = 420,
              height = 360,
              closed = true
            )
          val timeMin = allResults.minBy(_.time).time
          val timeMax = allResults.maxBy(_.time).time
          val xmin = stock.xscale(0)
          val xmax = stock.xscale(allResults.maxBy(_.param).param)
          val ymin = stock.yscale(0)
          val ymax = stock.yscale(timeMax)
          val allParams = allResults.map(_.param).distinct
          val dots = allParams map { p =>
            literal(x = stock.xscale(p), y = ymin, param = p)
          }
          val vNumDots = 5
          val vDots = (1 to vNumDots) map { c =>
            val time = (timeMin + c * (timeMax - timeMin) / vNumDots).toInt
            literal(x = xmin, y = stock.yscale(time), time = time)
          }
          import scala.scalajs.js.Dynamic.global
          global.console.log(stock.curves)
          vue.$set("curves", stock.curves)
          vue.$set("xmin", xmin)
          vue.$set("xmax", xmax)
          vue.$set("ymin", ymin)
          vue.$set("ymax", ymax)
          vue.$set("dots", dots.toJSArray)
          vue.$set("vdots", vDots.toJSArray)
        }
    }
}