# neo4j-eventstore

A Neo4j-backed [Quartz](https://github.com/vitorpamplona/amethyst) Nostr event store built for
**traversal**. Every event, user and address is a node. Every reference an event makes, through
its tags or its `nostr:` links, is a typed relationship. Queries can therefore hop across event
kinds: the notes my follows zapped, a thread's whole reply tree, the members of a community who
wear a badge.

It is designed to run beside [vespa-eventstore](https://github.com/NosFabrica/vespa-eventstore)
inside [vespa-relay](https://github.com/NosFabrica/vespa-relay), holding the same events. Vespa
serves REQ, COUNT and NIP-50 search. Neo4j serves multi-hop graph queries through a bounded
traversal language and a guarded, read-only Cypher endpoint. It does not index for full-text
search.

**Status:** design. Start with:

- [`docs/spec.md`](docs/spec.md): the graph schema, how edges are derived from Quartz's hint
  providers, the traversal language, and how the store stays in sync with Vespa.
- [`docs/plan.md`](docs/plan.md): the phased implementation plan and its exit gates.
- [`docs/appendix-providers.md`](docs/appendix-providers.md): every Quartz event class that
  implements `EventHintProvider` / `PubKeyHintProvider` / `AddressHintProvider`, and what it
  links.
