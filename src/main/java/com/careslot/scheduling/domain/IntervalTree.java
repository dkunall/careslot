package com.careslot.scheduling.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Static augmented interval tree: a balanced BST on interval start, each node also holding the latest end in
 * its subtree, so an overlap query skips whole subtrees and costs O(log n + k). Built once from a snapshot
 * (a day's leaves, a week's booked slots) and never mutated, which also makes it safe to share across threads.
 */
public final class IntervalTree<T> {

    private record Node<T>(Interval interval, T value, Node<T> left, Node<T> right, Instant maxEnd) {
    }

    private final Node<T> root;
    private final int size;

    private IntervalTree(Node<T> root, int size) {
        this.root = root;
        this.size = size;
    }

    public static <T> IntervalTree<T> of(List<T> items, Function<T, Interval> key) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(t -> key.apply(t).start()));
        return new IntervalTree<>(build(sorted, key, 0, sorted.size() - 1), sorted.size());
    }

    private static <T> Node<T> build(List<T> items, Function<T, Interval> key, int lo, int hi) {
        if (lo > hi) {
            return null;
        }
        int mid = (lo + hi) >>> 1;
        Node<T> left = build(items, key, lo, mid - 1);
        Node<T> right = build(items, key, mid + 1, hi);
        Interval iv = key.apply(items.get(mid));
        Instant max = iv.end();
        if (left != null && left.maxEnd.isAfter(max)) {
            max = left.maxEnd;
        }
        if (right != null && right.maxEnd.isAfter(max)) {
            max = right.maxEnd;
        }
        return new Node<>(iv, items.get(mid), left, right, max);
    }

    public int size() {
        return size;
    }

    public List<T> overlapping(Interval q) {
        List<T> out = new ArrayList<>();
        collect(root, q, out);
        return out;
    }

    public boolean anyOverlap(Interval q) {
        return any(root, q);
    }

    private void collect(Node<T> n, Interval q, List<T> out) {
        if (n == null || !n.maxEnd.isAfter(q.start())) {
            return; // nothing in this subtree ends after the query starts
        }
        collect(n.left, q, out);
        if (n.interval.overlaps(q)) {
            out.add(n.value);
        }
        if (n.interval.start().isBefore(q.end())) {
            collect(n.right, q, out); // right subtree starts later; only worth visiting if this one starts before q ends
        }
    }

    private boolean any(Node<T> n, Interval q) {
        if (n == null || !n.maxEnd.isAfter(q.start())) {
            return false;
        }
        if (n.interval.overlaps(q)) {
            return true;
        }
        return any(n.left, q) || (n.interval.start().isBefore(q.end()) && any(n.right, q));
    }
}
