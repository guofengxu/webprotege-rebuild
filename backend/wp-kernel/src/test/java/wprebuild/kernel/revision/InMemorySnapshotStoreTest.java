package wprebuild.kernel.revision;

class InMemorySnapshotStoreTest extends SnapshotStoreContract {

    @Override
    protected SnapshotStore newStore() {
        return new InMemorySnapshotStore();
    }
}
