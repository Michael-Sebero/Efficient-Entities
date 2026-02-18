package com.michaelsebero.efficiententities.util;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Lightweight array-backed LIFO stack.
 */
public class SimpleStack<T> {

    private static final int INITIAL_CAPACITY = 8;

    @SuppressWarnings("unchecked")
    private T[] data = (T[]) new Object[INITIAL_CAPACITY];
    private int top;

    public boolean isEmpty() { return top == 0; }
    public int     size()    { return top; }

    public void push(T element) {
        grow(top + 1);
        data[top++] = element;
    }

    public void pushAll(List<T> elements) {
        if (elements == null || elements.isEmpty()) return;
        int count = elements.size();
        grow(top + count);
        for (int i = 0; i < count; i++) data[top + i] = elements.get(i);
        top += count;
    }

    public T pop() {
        if (top == 0) throw new NoSuchElementException("Stack is empty");
        T element = data[--top];
        data[top] = null;
        return element;
    }

    public T peek() {
        if (top == 0) throw new NoSuchElementException("Stack is empty");
        return data[top - 1];
    }

    @SuppressWarnings("unchecked")
    private void grow(int required) {
        if (required <= data.length) return;
        data = (T[]) Arrays.copyOf(data, Math.max(data.length << 1, required), Object[].class);
    }
}
