package com.github.pxsdirac.cache

import akka.actor.typed.scaladsl.ActorContext
import com.github.pxsdirac.cache.CacheActor.{GetCachedValue, GetCachedValueResponse}

import scala.concurrent.Future
import scala.concurrent.duration.Deadline

trait CacheFutureApi[K,V,C] {
  protected def context:ActorContext[_]
  protected def actorName:String
  protected def refreshPolicy:RefreshPolicy[C]
  protected def missedPolicy:MissedPolicy[K,V,C]
  protected def getFromLocal(c: C,k: K):Option[V]

  private val actor = context.spawn(CacheActor(getFromLocal,refreshPolicy,missedPolicy),actorName)
  private implicit val system = context.system
  import akka.actor.typed.scaladsl.AskPattern._
  import akka.util.Timeout
  import system.executionContext

  def get(k: K)(implicit deadline:Deadline):Future[Option[V]] = {
    implicit val timeout = Timeout(deadline.timeLeft)
    actor.ask[GetCachedValueResponse[V]](ref => GetCachedValue(k,ref)).map(_.value)
  }
}
