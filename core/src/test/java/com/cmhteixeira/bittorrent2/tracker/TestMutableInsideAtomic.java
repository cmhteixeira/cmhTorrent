package com.cmhteixeira.bittorrent2.tracker;

import java.util.List;


public class TestMutableInsideAtomic {

  public static void main(String[] args) throws InterruptedException {
    TestJava testJava = new TestJava();
    Runnable runnable =
        new Runnable() {
          @Override
          public void run() {
            int limit = 0;
            while (limit < 10000) {
              testJava.goodDev();
              ++limit;
            }
          }
        };

    Thread threadA = new Thread(runnable, "A");
    Thread threadB = new Thread(runnable, "B");

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    List<String> res = testJava.getState().get("foo");
    int num = res.size();
    System.out.println(num);
  }
}
