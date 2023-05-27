package com.cmhteixeira
import java.util.concurrent.atomic.AtomicReference

class Test {

  val sharedStateImmut = new AtomicReference[Map[String, List[String]]](Map("foo" -> List.empty))

  def goodDevImmut: Map[String, List[String]] = {
    val currentMap = sharedStateImmut.get()

    val newMap = currentMap + ("foo" -> Li)

  }
  def getStateImmut: Map[String, List[String]] = sharedStateImmut.get
}
{}
