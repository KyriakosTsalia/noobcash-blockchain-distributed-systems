package noobcash

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.stream.Materializer
import com.typesafe.config.{Config, ConfigFactory}
import noobcash.actors.Peer

final case class PeerApp(hostname: String, port: Int, isBootstrap: Boolean, NETWORK_SIZE: Int, CAPACITY: Int, DIFFICULTY: Int) extends NBCHttpRoute {

  val config: Config = ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.hostname = "$hostname",
       |akka.remote.artery.canonical.port = $port,
       |akka.cluster.roles = [${if (isBootstrap) "bootstrap" else ""}]
    """.stripMargin
  ).withFallback(ConfigFactory.load())

  implicit val system: ActorSystem = ActorSystem("NBCCluster", config)
  implicit val materializer: Materializer = Materializer(system)

  val peer: ActorRef = system.actorOf(Peer.props(Wallet(), NETWORK_SIZE, CAPACITY, DIFFICULTY), "peer")

  Http().bindAndHandle(nbcRoute, hostname, 3000 + port % NETWORK_SIZE)
}

object NoobcashApp extends App {
  if (args.length != 6)
    println("Error in starting the noobcash app, some parameters were left unspecified, aborting...")
  else {
    val hostname: String = args(0).toString
    val port: Int = args(1).toInt
    val isBootstrap: Boolean = args(2).toBoolean
    val NETWORK_SIZE: Int = args(3).toInt
    val CAPACITY: Int = args(4).toInt
    val DIFFICULTY: Int = args(5).toInt

    PeerApp(hostname, port, isBootstrap, NETWORK_SIZE, CAPACITY, DIFFICULTY)
  }
}