package sample;

import sample.util.Checks;

import java.util.ArrayList;
import java.util.List;

public class Hello implements Runnable {
	private static final String SECRET = "s3cr3t-token";
	private final List<String> names = new ArrayList<>();

	public static void main(String[] args) {
		new Hello().run();
	}

	@Override
	public void run() {
		names.add(SECRET);
		if (Checks.isAdmin(System.getProperty("user.name")))
			System.out.println("admin mode");
		System.out.println(greet("world", 3));
	}

	String greet(String who, int times) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < times; i++)
			sb.append("Hello ").append(who).append('\n');
		return sb.toString();
	}
}
