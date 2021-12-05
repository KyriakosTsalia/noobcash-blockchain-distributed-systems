package noobcash

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.stream.Materializer
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import noobcash.transaction.{TransactionHttp, TransactionInput}

protected object HttpResponses {
  val TRANSACTION_ATTEMPT_RECORDED: HttpEntity.Strict =
    HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      """
        |<html>
        | <body>
        |   Your transaction attempt was recorded, it will be processed and verified shortly...
        |   If sufficient funds are found, look for it in the future valid blocks.
        |   Otherwise, it will be discarded.
        | </body>
        |</html>
      """.stripMargin
    )

  val TRANSACTION_ATTEMPT_DISCARDED: HttpEntity.Strict =
    HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      """
        |<html>
        | <body>
        |   Your transaction attempt was discarded because:
        |     - the receiver you were looking for was not found,
        |     or
        |     - you were trying to send money to yourself, causing needless traffic in the network
        | </body>
        |</html>
      """.stripMargin
    )

  val BALANCE: Int => HttpEntity.Strict = balance =>
    HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      s"""
         |<html>
         | <body>
         |   You currently have $balance NBC
         | </body>
         |</html>
            """.stripMargin
    )

  val HELP: HttpEntity.Strict =
    HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      """
        |These are the following actions :
        |t <recipient_address> <amount> : To send <amount> NBCs to node <recipient_address>
        |view : To view all transactions in latest verified block
        |balance : To check your wallet's current balance
        |help : To get some help
      """.stripMargin
    )
}

trait TransactionJsonProtocol extends DefaultJsonProtocol {
  implicit val transactionInputFormat: RootJsonFormat[TransactionInput] = jsonFormat1(TransactionInput)
  implicit val transactionFormat: RootJsonFormat[TransactionHttp] = jsonFormat4(TransactionHttp)
}

trait NBCHttpRoute extends TransactionJsonProtocol with SprayJsonSupport {
  import noobcash.actors.Peer._
  import HttpResponses._

  implicit val system: ActorSystem
  implicit val materializer: Materializer
  implicit lazy val timeout: Timeout = Timeout(3 seconds)
  implicit lazy val executionContext: ExecutionContext = system.dispatcher

  val peer: ActorRef

  lazy val nbcRoute: Route =
    (pathPrefix("api") & get) {
      path("transaction") {
        parameter('rec.as[String], 'amount.as[Int]) { (receiver, amount) =>
          complete((peer ? TransactionRequest(receiver, amount)).map {
            case TransactionAttemptRecorded => TRANSACTION_ATTEMPT_RECORDED
            case TransactionAttemptDiscarded => TRANSACTION_ATTEMPT_DISCARDED
          })
        }
      } ~
      path("view") {
        complete((peer ? View).mapTo[List[TransactionHttp]])
      } ~
      path("balance") {
        complete((peer ? Balance).mapTo[Int].map(BALANCE))
      } ~
      path("help") {
        complete(HELP)
      } ~
      path("wallets") {
        complete((peer ? ReportWallets).mapTo[Map[String, Int]])
      }
    }
}
