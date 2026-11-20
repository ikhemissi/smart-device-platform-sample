/// Fails the image build when the runtime's Java is older than the given minimum version.
/// Run with the single-file source launcher: `java RequireJavaVersion.java 25.0.5.1`.
void main(String[] args) {
    var minimum = Runtime.Version.parse(args[0]);
    var actual = Runtime.version();
    if (actual.compareTo(minimum) < 0) {
        System.err.println("Java " + actual + " is older than the required " + minimum + "; pull a newer base image.");
        System.exit(1);
    }
    System.out.println("Java " + actual + " meets the minimum " + minimum + ".");
}
