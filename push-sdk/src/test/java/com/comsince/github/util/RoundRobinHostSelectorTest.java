package com.comsince.github.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RoundRobinHostSelectorTest {

    @Test
    public void currentHostIsNullBeforeFirstSelection() {
        RoundRobinHostSelector selector = new RoundRobinHostSelector("a.example.com:b.example.com");
        assertNull(selector.getCurrentHost());
    }

    @Test
    public void currentHostMatchesLastSelection() {
        RoundRobinHostSelector selector = new RoundRobinHostSelector("a.example.com:b.example.com");
        String first = selector.getNextHost();
        assertEquals(first, selector.getCurrentHost());
        String second = selector.getNextHost();
        assertEquals(second, selector.getCurrentHost());
    }

    @Test
    public void currentHostSurvivesWrapAround() {
        RoundRobinHostSelector selector = new RoundRobinHostSelector("a.example.com:b.example.com");
        selector.getNextHost();
        selector.getNextHost();
        String third = selector.getNextHost();
        assertEquals("a.example.com", third);
        assertEquals("a.example.com", selector.getCurrentHost());
    }

    @Test
    public void singleHostAlwaysReturnsSameHost() {
        RoundRobinHostSelector selector = new RoundRobinHostSelector("only.example.com");
        assertEquals("only.example.com", selector.getNextHost());
        assertEquals("only.example.com", selector.getNextHost());
        assertEquals("only.example.com", selector.getCurrentHost());
    }
}
