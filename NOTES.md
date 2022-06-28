(unstructured log of notes to be integrated in docs)

- **idents as refs**: re-db will store keywords directly as entity-ids in the ref position. Entity and Pull will return keywords (not entities or db/id's) when following refs.
- pull syntax: `:db/id true` can be used on a unique-identity attribute to indicate that 