package com.github.pxsdirac.cache

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.github.pxsdirac.cache.MissedPolicy.{FetchMissedAndUpdate, JustReturnNone}
import com.github.pxsdirac.cache.RefreshPolicy.{LoadAndKeep, NoRefresh, ReloadAndUpdate}

import scala.concurrent.Future
import scala.util.{Failure, Success}

object CacheActor {
  case class GetCachedValueResponse[V](value:Option[V])
  sealed trait Command[K,V,C]

  sealed trait PublicCommand[K,V,C] extends Command[K,V,C]
  case class GetCachedValue[K,V,C](key: K, replyTo:ActorRef[GetCachedValueResponse[V]]) extends PublicCommand[K,V,C]

  private[cache] sealed trait PrivateCommand[K,V,C] extends Command[K,V,C]

  private[cache] case class LoadSucceed[K,V,C](c:C) extends PrivateCommand[K,V,C]
  private[cache] case class LoadFailed[K,V,C](throwable: Throwable) extends PrivateCommand[K,V,C]

  private[cache] case class FetchMissedSucceed[K,V,C](k:K,v: Option[V], replyTo:Option[ActorRef[GetCachedValueResponse[V]]]) extends PrivateCommand[K,V,C]
  private[cache] case class FetchMissedFailed[K,V,C](k: K,replyTo:Option[ActorRef[GetCachedValueResponse[V]]]) extends PrivateCommand[K,V,C]

  private[cache] case class Refresh[K,V,C]() extends PrivateCommand[K,V,C]

  def apply[K,V,C](getter: (C,K) => Option[V],refreshPolicy: RefreshPolicy[C], missedPolicy: MissedPolicy[K,V,C]):Behavior[Command[K,V,C]] = init(getter,refreshPolicy,missedPolicy)

  private def init[K,V,C](getter: (C,K) => Option[V],refreshPolicy: RefreshPolicy[C], missedPolicy: MissedPolicy[K,V,C]):Behavior[Command[K,V,C]] = {
    type CMD = Command[K,V,C]
    def loadBehavior(loader:() => Future[C], stashCapacity:Int):Behavior[CMD] = Behaviors.setup{ context =>
      import context.executionContext
      import context.log
      log.debug("initializing...")
      loader().onComplete{
        case Success(value) =>
          context.self ! LoadSucceed(value)
        case Failure(exception) =>
          context.self ! LoadFailed(exception)
      }
      Behaviors.withStash[CMD](stashCapacity){stashBuffer =>
        Behaviors.receiveMessagePartial{
          case LoadSucceed(c) =>
            log.debug("initialized.")
            stashBuffer.unstashAll(working[K,V,C](c,getter,refreshPolicy,missedPolicy))
          case LoadFailed(throwable) =>
            log.debug("initialize failed.")
            throwable.printStackTrace()
            context.stop(context.self)
            Behaviors.empty[CMD]
          case req @ GetCachedValue(_,_) =>
            stashBuffer.stash(req)
            Behaviors.same
        }
      }
    }
    refreshPolicy match {
        case NoRefresh(empty) =>
          working[K,V,C](empty,getter,refreshPolicy,missedPolicy)
        case ReloadAndUpdate(interval,loader, _, stashCapacity) =>
          Behaviors.withTimers{timer =>
            timer.startSingleTimer(Refresh(),interval)
            loadBehavior(loader,stashCapacity)
          }
        case LoadAndKeep(loader, stashCapacity) =>
          loadBehavior(loader,stashCapacity)
      }
    }


  private def working[K,V,C](c:C, getter: (C,K) => Option[V],refreshPolicy: RefreshPolicy[C], missedPolicy: MissedPolicy[K,V,C]):Behavior[Command[K,V,C]] = {
    type CMD = Command[K,V,C]
    type PF = PartialFunction[CMD,Behavior[CMD]]

    Behaviors.setup[CMD]{context =>
      import context.executionContext
      import context.log
      def handleGet:PF = {
        case GetCachedValue(key,replyTo) =>
          (getter(c,key), missedPolicy) match {
            case (v @ Some(_),_) =>
              log.debug("fit cache.")
              replyTo ! GetCachedValueResponse(v)
              Behaviors.same
            case (None , JustReturnNone) =>
              log.debug("cache missed, returning.")
              replyTo ! GetCachedValueResponse(None)
              Behaviors.same
            case (None, FetchMissedAndUpdate(fetcher,_)) =>
              log.debug("cache missed, fetching.")
              fetcher(key).onComplete{
                case Success(v) =>
                  context.self ! FetchMissedSucceed(key,v,Some(replyTo))
                case Failure(throwable) =>
                  throwable.printStackTrace()
                  context.self ! FetchMissedFailed(key,Some(replyTo))
              }
              Behaviors.same
          }
      }
      def handleMissed:PF = {
        case FetchMissedSucceed(k, vOpt, replyToOpt) =>
          log.debug("missed cache fetched.")
          replyToOpt.foreach(replyTo => replyTo ! GetCachedValueResponse(vOpt))
          missedPolicy match {
            case JustReturnNone =>
              // impossible
              Behaviors.same
            case FetchMissedAndUpdate(_, update) =>
              val updated = vOpt.map(v => update(c,k,v)).getOrElse(c)
              working(updated,getter,refreshPolicy,missedPolicy)
          }
        case FetchMissedFailed(_,replyToOpt) =>
          log.debug("missed cache fetched failed.")
          replyToOpt.foreach(replyTo => replyTo ! GetCachedValueResponse(None))
          Behaviors.same
      }
      def handleRefresh:PF =  {
        refreshPolicy match {
          case ReloadAndUpdate(interval,loader,updater,_) =>
            import context.executionContext

            {
              case Refresh() =>
                log.debug("refreshing.")

                loader().onComplete{
                  case Success(c) =>
                    context.self ! LoadSucceed(c)
                  case Failure(throwable) =>
                    context.self ! LoadFailed(throwable)
                }
                Behaviors.same

              case LoadSucceed(newC) =>
                log.debug("refreshed.")

                val updated = updater(c,newC)
                Behaviors.withTimers{ timer =>
                  timer.startSingleTimer(Refresh(),interval)
                  working(updated,getter,refreshPolicy,missedPolicy)
                }

              case LoadFailed(throwable) =>
                log.debug("refresh failed.")
                throwable.printStackTrace()
                Behaviors.withTimers{timer =>
                  timer.startSingleTimer(Refresh(),interval)
                  working(c,getter,refreshPolicy,missedPolicy)
                }
            }
          case _ =>
            // impossible
          {
            case Refresh() => Behaviors.same
            case LoadFailed(_) => Behaviors.same
            case LoadSucceed(_) => Behaviors.same
          }
        }
      }

      Behaviors.receiveMessagePartial(handleGet.orElse(handleMissed).orElse(handleRefresh))
    }
  }



}
