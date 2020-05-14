package com.github.pxsdirac.cache

import scala.concurrent.Future

sealed trait MissedPolicy[K,V,C]

object MissedPolicy{
  case object JustReturnNone extends MissedPolicy[Any,Nothing,Nothing]

  case class FetchMissedAndUpdate[K,V,C](fetcher:K => Future[Option[V]], updater: (C,K,V) => C)
    extends MissedPolicy[K,V,C]
}
