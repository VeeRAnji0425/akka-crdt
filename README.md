# Akka CRDT

Server-managed CRDT implementation for Akka

## TODO

The "Specification" below refers to the paper 'A comprehensive study of Convergent and Commutative Replicated Data Types' by Mark Shapiro et. al.

[http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf]

### Convergent (state-based)

* DONE Specification 6  State-based increment-only counter
* DONE Specification 7  State-based PN-Counter
* Specification 8       State-based Last-Writer-Wins Register (LWW Register)
* Specification 10      State-based Multi-Value Register (MV-Register)
* DONE Specification 11 State-based grow-only Set (G-Set)
* DONE Specification 12 State-based 2P-Set
* DONE Use DistributedPubSub for broadcast of changes

### Commutative (ops-based)

* Specification 5 op-based Counter
* Specification 9 Op-based LWW-Register
* Specification 13 U-Set: Op-based 2P-Set with unique elements
* Specification 14 Molli, Weiss, Skaf Set
* Specification 15 Op-based Observed-Remove Set (OR-Set)
* Specification 16 2P2P-Graph (op-based)
* Specification 17 Add-only Monotonic DAG (op-based)
* Specification 18 Add-Remove Partial Order
* Specification 19 Replicated Growable Array (RGA)
* Specification 21 Op-based Observed-Remove Shopping Cart (OR-Cart)

### Garbage Collection
* Needs consensus? Performed by Leader/Singleton?

### Reliable Broadcast
* Using eventsourced

### Misc

* Add "format": "string"/"json"/"binary"
* Is java.util.UUID a good id generator? 
* How should we manage JSON marshalling errors?
* Fix documentation
* Support DELETE of a CRDT?

# DOCUMENTATION

## Sets

### G-Set

Set union is commutative and convergent; hence it is always safe to have simultaneous writes to a set *which only allows addition*. You cannot remove an element of a G-Set.

JSON:

``` javascript
{
  'type': 'g-set',
  'e': ['a', 'b', 'c']
}
```

2P-Set
---

2-phase sets consist of two g-sets: one for adding, and another for removing. To add an element, add it to the add set A. To remove e, add e to the remove set R.  An element can only be added once, and only removed once. Elements can only be removed if they are present in the set. Removes take precedence over adds.

JSON:

``` javascript
{
  'type': '2p-set',
  'a': ['a', 'b'],
  'r': ['b']
}
```

In this set, only 'a' is present.

LWW-Element-Set
---

LWW-Element-Set is like 2P-Set: it comprises an add G-Set (A) and a remove
G-Set (R), with a timestamp for each element. To add an element e, add (e,
timestamp) to the add set A. To remove e, add (e, timestamp) to the remove set
R. An element is present iff it is in A, and no *newer* element exists in R.
Merging is accomplished by taking the union of all A and all R, respectively.

Since the last write wins, we can safely take only the largest add, and the
largest delete. All others can be pruned.

When A and R have equal timestamps, the direction of the inequality determines
whether adds or removes win. {'bias': 'a'} indicates that adds win. {'bias':
'r'} indicates that removes win. The default bias is 'a'.

Timestamps may be *any* ordered primitive: integers, floats, strings, etc. If a
coordinated unique timestamp service is used, LWW-Element-Set behaves like a
traditional consistent Set structure. If non-unique timestamps are used, the
resolution of the timestamp determines the window under which conflicts will be
resolved by the bias towards adds or deletes.

TODO: define sorting strategies for strings. By byte value, UTF-8 ordering,
numeric, etc...

In JSON, we write the set as a list of 2- or 3-tuples: [element, add-time] or
[element, add-time, delete-time]

JSON:

``` javascript
{
  'type': 'lww-e-set',
  'bias': 'a',
  'e': [
    ['a', 0],
    ['b', 1, 2],
    ['c', 2, 1],
    ['d', 3, 3]
  ]
}
```

In this set:

- a was created at 0 and still exists.
- b was deleted after creation; it does not exist.
- c was created after deletion; it exists
- d was created and deleted at the same time. Bias a means we prefer adds, so it exists.

OR-Set
---

Observed-Removed Sets support adding and removing elements in a causally
consistent manner. It resembles LWW-Set, except that instead of times, unique
tags are associated with each insertion or deletion. In the case of conflicting
add and delete, add wins. An element is a member of the set iff the set of
insertion tags less the set of deletion tags is nonempty.

We write the set as a list of 2- or 3- tuples: [element, [add-tags]] or
[element, [add-tags], [remove-tags]]

To insert e, generate a unique tag, and add it to the insertion tag set for e.

To remove e, take all insertion tags for e, and insert them into the deletion
tags for e.

To merge two OR-Sets, for each element in either set, take the union of the
insertion tags and the union of the deletion tags.

Tags may be any primitive: strings, ints, floats, etc.

JSON:

``` javascript
{
  'type': 'or-set',
  'e': [
    ['a', [1]],
    ['b', [1], [1]],
    ['c', [1, 2], [2, 3]]
  ]
}
```

- a exists.
- b's only insertion was deleted, so it does not exist.
- c has two insertions, only one of which was deleted. It exists.

Max-Change-Sets
---

MC-Sets resolve divergent histories for an element by choosing the value which
has changed the most. You cannot delete an element which is not present, and
cannot add an element which is already present. MC-sets are compact and do the
right thing when changes to elements are infrequent compared to the conflict
resolution window, but behave arbitrarily when divergent histories each include
many changes.

Each element e is associated with an integer n, implicitly assumed to be zero.
When n is even, the element is absent from the set. When n is odd, the element
is present. To add an element to the set, increment n from an even value by
one; to remove an element, increment n from an odd value by one. To merge sets,
take each element and choose the maximum value of n from each history.

When n is limited to [0, 2], Max-Change-Sets collapse to 2P-Sets. Unlike
2P-Sets, however, one can add and remove an arbitrary number of times. The
disadvantage is that there is no bias towards preserving adds or removes.
Instead, whichever history has incremented further (undergone more changes) is
preferred.

In JSON, max-change sets are represented as a list of [element, n] tuples.

JSON:

``` javascript
{
  'type': 'mc-set',
  'e': [
    ['a', 1],
    ['b', 2],
    ['c', 3]
  ]
}
```

- a is present
- b is absent
- c is present

Counters
===

G-Counter
---

A G-Counter is a grow-only counter (inspired by vector clocks) in
which only increment and merge are possible. Incrementing the counter
adds 1 to the count for the current actor. Divergent histories are
resolved by taking the maximum count for each actor (like a vector
clock merge).  The value of the counter is the sum of all actor
counts.

JSON:

``` javascript
{
  'type': 'g-counter',
  'e': {
    'a': 1,
    'b': 5,
    'c': 2
  }
}
```

- The counter value is 8.

PN-Counter
---

PN-Counters allow the counter to be decreased by tracking the
increments (P) separate from the decrements (N), both represented as
internal G-Counters.  Merge is handled by merging the internal P and N
counters. The value of the counter is the value of the P counter minus
the value of the N counter.

JSON:

``` javascript
{
  'type': 'pn-counter',
  'p': {
    'a': 10,
    'b': 2
  },
  'n': {
    'c': 5,
    'a': 1
  }
}
```

- P=12, N=6, so the value is 6.

