package com.cmhteixeira

object TestImmutableInsideAtomic {

  def main(args: Array[String]): Unit = {
    val test = new Test()
    val runnable =
      new Runnable() {

        def run(): Unit = {
          var limit = 0
          while (limit < 100000) {
            test.goodDevImmut(limit)
            limit = limit + 1
          }
        }
      }

    val threadA = new Thread(runnable, "A")
    val threadB = new Thread(runnable, "B")

    threadA.start()
    threadB.start()
    threadA.join()
    threadB.join()

    val res = test.getStateImmut("foo")
    println(res.size)

  }
}
