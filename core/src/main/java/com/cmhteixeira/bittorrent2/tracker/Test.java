package com.cmhteixeira.bittorrent2.tracker;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class Test {
  AtomicReference<Map<String, List<String>>> sharedState;
  AtomicReference<ImmutableMap<String, ImmutableList<String>>> sharedStateImmut;

  public Test() {
    Map<String, List<String>> hashMap = new HashMap<>();
    hashMap.put("foo", new ArrayList<>());
    this.sharedState = new AtomicReference<>(hashMap);
    this.sharedStateImmut = new AtomicReference<>(ImmutableMap.of("foo", ImmutableList.of()));
  }

  public void badDev() {
    Map<String, List<String>> theMap = sharedState.get();
    List<String> theList = theMap.get("foo");
    if (theList != null) {
      theList.add("bar");
    } else {
      List<String> newList = new ArrayList<>();
      newList.add("bar");
      theMap.put("key", newList);
    }
  }

  public Map<String, List<String>> distractedDev() {
    Map<String, List<String>> currentMap = sharedState.get();
    Map<String, List<String>> theNewMap = new HashMap<>();

    for (Map.Entry<String, List<String>> entry : currentMap.entrySet()) {
      String theKey = entry.getKey();
      List<String> theValue = entry.getValue();
      theValue.add("monkey");
      theNewMap.put(theKey, theValue);
    }

    if (!sharedState.compareAndSet(currentMap, theNewMap)) distractedDev();
    return theNewMap;
  }

  public Map<String, List<String>> goodDev() {
    Map<String, List<String>> currentMap = sharedState.get();
    Map<String, List<String>> theNewMap = new HashMap<>();

    for (Map.Entry<String, List<String>> entry : currentMap.entrySet()) {
      String theKey = entry.getKey();
      List<String> newList = new ArrayList<>(entry.getValue());
      newList.add("monkey");
      theNewMap.put(theKey, newList);
    }

    if (!sharedState.compareAndSet(currentMap, theNewMap)) goodDev();
    return theNewMap;
  }

  public ImmutableMap<String, ImmutableList<String>> goodDevImmut() {
    ImmutableMap<String, ImmutableList<String>> currentMap = sharedStateImmut.get();
    ImmutableMap.Builder<String, ImmutableList<String>> theNewMapBuilder = ImmutableMap.builder();

    for (Map.Entry<String, ImmutableList<String>> entry : currentMap.entrySet()) {
      String theKey = entry.getKey();
      ImmutableList.Builder<String> newList = ImmutableList.builder();
      newList.addAll(entry.getValue());
      newList.add("monkey");
      theNewMapBuilder.put(theKey, newList.build());
    }

    ImmutableMap<String, ImmutableList<String>> theNewMap = theNewMapBuilder.build();
    if (!sharedStateImmut.compareAndSet(currentMap, theNewMap)) goodDevImmut();
    return theNewMap;
  }

  public Map<String, List<String>> getState() {
    return sharedState.get();
  }

  public ImmutableMap<String, ImmutableList<String>> getStateImmut() {
    return sharedStateImmut.get();
  }
}
