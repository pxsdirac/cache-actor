package com.github.pxsdirac.cache

import akka.actor.typed.ActorSystem
import com.github.pxsdirac.cache.CacheActor.{GetCachedValue, GetCachedValueResponse}
import com.github.pxsdirac.cache.MissedPolicy.FetchMissedAndUpdate
import com.github.pxsdirac.cache.RefreshPolicy.ReloadAndUpdate

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.{Source, StdIn}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object Main extends App {
  // modify the content of kv.csv in runtime to see how cache actor works

  def loadAllKVs():Future[Map[String,String]] = Future{
    val source = Source.fromFile("kv.csv")
    val map = source.getLines().map{line =>
      val Array(k,v) = line.split(",")
      (k,v)
    }.toMap
    source.close()
    map
  }

  def loadByKey(key:String):Future[Option[String]] = loadAllKVs().map(all => all.get(key))

  def get(map:Map[String,String],key:String):Option[String] = map.get(key)

  val refreshPolicy = ReloadAndUpdate[Map[String,String]](10.seconds,loadAllKVs,_ ++ _, 1000)
  val missedPolicy = FetchMissedAndUpdate[String,String,Map[String,String]](loadByKey,(map,k,v) => map + (k ->v))

  val behavior = CacheActor(get,refreshPolicy,missedPolicy)

  import akka.actor.typed.scaladsl.AskPattern._
  import akka.util.Timeout

  implicit val timeout: Timeout = 3.seconds
  implicit val system = ActorSystem(behavior,"cache-actor")
  while (true){
    val key = StdIn.readLine()
    val future = system.ask[GetCachedValueResponse[String]](ref => GetCachedValue(key,ref)).map(_.value)
    future.onComplete{
      case Success(value) => println(value)
      case Failure(exception) => exception.fillInStackTrace()
    }
  }

}
