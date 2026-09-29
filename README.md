# neo4j-eventstore

A graph projection of the Nostr events held by
[vespa-eventstore](https://github.com/NosFabrica/vespa-eventstore) in
[vespa-relay](https://github.com/NosFabrica/vespa-relay), queried through Cypher.

- **Nodes.** Every event, user and address is a node.
- **Relationships.** Every reference an event makes is a typed relationship: its tags, as
  resolved by Quartz's hint providers, and its `nostr:` links. Queries can therefore join event
  kinds, e.g. the notes my follows zapped, a thread's whole reply tree, or the users a NIP-85
  provider ranks highly.
- **Not a relay.** Neo4j is not a relay and holds no event bodies. Vespa stays the source of
  truth: Neo4j follows Vespa's writes and removals and is reconciled against it, and Cypher
  results are hydrated from Vespa.

**Status:** design. Start with:

- [`docs/spec.md`](docs/spec.md): the graph schema, how edges are derived from Quartz's hint
  providers, how the projection stays exact, and the guarded read-only Cypher endpoint.
- [`docs/plan.md`](docs/plan.md): the phased implementation plan and its exit gates.
- [`docs/appendix-providers.md`](docs/appendix-providers.md): every Quartz event class that
  implements `EventHintProvider` / `PubKeyHintProvider` / `AddressHintProvider`, and what it
  links.
