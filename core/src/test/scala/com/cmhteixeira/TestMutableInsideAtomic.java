package com.cmhteixeira;

import com.cmhteixeira.bittorrent2.tracker.Test;

import java.util.List;

public class TestMutableInsideAtomic {

  public static void main(String[] args) throws InterruptedException {
    Test test = new Test();
    Runnable runnable =
        new Runnable() {
          @Override
          public void run() {
            int limit = 0;
            while (limit < 100000) {
              test.goodDevImmut();
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

    List<String> res = test.getStateImmut().get("foo");
    int num = res.size();
    System.out.println(num);

  }
}
