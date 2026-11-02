/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NodeStackTest {

  @Test
  void pushAndRetireInPlace() {
    NodeStack stack = new NodeStack(2);
    ElementNode first = stack.pushSlot();
    first.tagId = Consts.TAG_DIV;
    stack.retire();

    ElementNode second = stack.pushSlot();
    second.tagId = Consts.TAG_P;
    assertTrue(stack.size() == 1);
    assertEquals(Consts.TAG_P, stack.get(0).tagId);

    assertTrue(stack.retire());
    assertEquals(Consts.TAG_P, stack.retired().tagId);
    assertFalse(stack.retire());
  }

  @Test
  void retiredBuffersAreReleased() {
    NodeStack stack = new NodeStack();
    ElementNode node = stack.pushSlot();
    node.attributes.insertKnown(Consts.ATTR_HREF, "x");
    node.extras = new NodeExtras("custom");
    stack.retire();
    stack.releaseRetired();
    assertNull(stack.retired().attributes.getBit(Consts.ATTR_HREF));
    assertNull(stack.retired().extras);
  }

  @Test
  void takeAndRestoreRetired() {
    NodeStack stack = new NodeStack();
    stack.pushSlot().tagId = Consts.TAG_A;
    stack.retire();
    ElementNode taken = stack.takeRetired();
    assertEquals(Consts.TAG_A, taken.tagId);
    assertEquals(-1, stack.retired().tagId);
    stack.restoreRetired(taken);
    assertEquals(Consts.TAG_A, stack.retired().tagId);
  }

  @Test
  void popReturnsNullWhenEmpty() {
    NodeStack stack = new NodeStack();
    assertNull(stack.pop());
    stack.push(new ElementNode());
    assertNotNull(stack.pop());
    assertEquals(0, stack.size());
  }
}
