package wprebuild.kernel.revision;

class InMemoryRevisionLogTest extends RevisionLogContract {

    @Override
    protected RevisionLog newLog() {
        return new InMemoryRevisionLog();
    }
}
