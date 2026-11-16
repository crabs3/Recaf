package sample.util;

public final class Checks {
	private Checks() {}

	public static boolean isAdmin(String user) {
		return "root".equals(user);
	}

	public static int magic() {
		return 1337;
	}
}
