/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/**
 * The open-element stack, with closed nodes kept in place past its end.
 *
 * <p>Closed nodes stay in their slots, readable through {@link #retired()}. The live region is
 * {@code [0, size())}.
 */
final class NodeStack {

  private static final ElementNode[] EMPTY = new ElementNode[0];

  private ElementNode[] nodes = EMPTY;
  private int len;

  NodeStack() {}

  NodeStack(int capacity) {
    if (capacity > 0) {
      nodes = new ElementNode[capacity];
    }
  }

  int size() {
    return len;
  }

  boolean isEmpty() {
    return len == 0;
  }

  ElementNode get(int index) {
    return nodes[index];
  }

  void set(int index, ElementNode node) {
    nodes[index] = node;
  }

  ElementNode pushSlot() {
    if (len == nodes.length) {
      grow(len + 1);
    }
    if (nodes[len] == null) {
      nodes[len] = new ElementNode();
    }
    len++;
    return nodes[len - 1];
  }

  void push(ElementNode node) {
    if (len == nodes.length) {
      grow(len + 1);
    }
    nodes[len] = node;
    len++;
  }

  /** Close the top node in place. */
  boolean retire() {
    if (len == 0) {
      return false;
    }
    len--;
    return true;
  }

  ElementNode retired() {
    return nodes[len];
  }

  /**
   * Drop a retired node's large values and extras once its exit is emitted. Otherwise a slot above
   * the live depth holds them until the document ends.
   */
  void releaseRetired() {
    ElementNode node = nodes[len];
    node.attributes.clear();
    node.extras = null;
  }

  /** Move the retired node out, leaving an empty node in its slot. */
  ElementNode takeRetired() {
    ElementNode node = nodes[len];
    nodes[len] = new ElementNode();
    return node;
  }

  /** Put a node taken by {@link #takeRetired()} back, so its buffers are reused. */
  void restoreRetired(ElementNode node) {
    nodes[len] = node;
  }

  /** Retire and take the top node, for callers that need it owned. */
  ElementNode pop() {
    return retire() ? takeRetired() : null;
  }

  private void grow(int needed) {
    int capacity = Math.max(needed, Math.max(8, nodes.length * 2));
    ElementNode[] grown = new ElementNode[capacity];
    System.arraycopy(nodes, 0, grown, 0, nodes.length);
    nodes = grown;
  }
}
