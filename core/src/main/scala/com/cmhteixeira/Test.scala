package com.cmhteixeira
import java.util.concurrent.atomic.AtomicReference

class Test {

  private val sharedStateImmut = new AtomicReference[Map[String, Set[String]]](Map("foo" -> Set.empty))

  def goodDevImmut(iter: Int): Map[String, Set[String]] = {
    val currentMap = sharedStateImmut.get()

    val newMap = currentMap + ("foo" -> (currentMap("foo") + s"monkey-${Thread.currentThread().getName}-$iter"))

    if (!sharedStateImmut.compareAndSet(currentMap, newMap)) goodDevImmut(iter)
    else newMap

  }
  def getStateImmut: Map[String, Set[String]] = sharedStateImmut.get
}
