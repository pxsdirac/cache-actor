package com.github.pxsdirac.cache

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}

sealed trait RefreshPolicy[C]

object RefreshPolicy{
  case class NoRefresh[C](empty:C) extends RefreshPolicy[C]

  case class ReloadAndUpdate[C](interval:FiniteDuration, loader:() => Future[C], updater:(C,C) => C, initStashCapacity:Int)
    extends RefreshPolicy[C]

  case class LoadAndKeep[C](loader:() =>Future[C],initStashCapacity:Int) extends
    RefreshPolicy[C]
}
