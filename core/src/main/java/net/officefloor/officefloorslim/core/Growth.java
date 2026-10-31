package net.officefloor.officefloorslim.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Growth from git history (DESIGN.md, Comparing the two costs): over the window, the lines of a
 * method that were written in it and the commits that wrote them. The window is the last
 * {@code n} commits or the last 12 months, whichever is shorter (decided; {@code n} = 500 by
 * default).
 *
 * <p>
 * Read from {@code git blame} at {@code HEAD}, so it counts the lines that survive: a line
 * written and then rewritten in the window counts once, for its last commit.
 */
public final class Growth {

	/** The default number of commits in the window. */
	public static final int DEFAULT_COMMITS = 500;

	/**
	 * A method's growth.
	 *
	 * @param linesAdded its code lines written in the window
	 * @param commits    the commits in the window that wrote them
	 */
	public record Of(int linesAdded, int commits) {
	}

	private final Path root;
	private final Set<String> window;

	private Growth(Path root, Set<String> window) {
		this.root = root;
		this.window = window;
	}

	/** The history of the repository at {@code root}; null when it is not a git work tree with a commit. */
	public static Growth of(Path root, int commits) {
		List<String> ids = git(root, "rev-list", "--max-count=" + commits, "--since=12.months", "HEAD");
		if (ids == null) {
			return null;
		}
		return new Growth(root, new LinkedHashSet<>(ids));
	}

	/** The commits in the window, newest first. */
	public Set<String> window() {
		return window;
	}

	/**
	 * The commit that wrote each line of the file at {@code HEAD}, by line number (index 0 unused);
	 * null when the file is not in {@code HEAD}.
	 */
	public String[] blame(String path) {
		List<String> out = git(root, "blame", "--porcelain", "HEAD", "--", path);
		if (out == null) {
			return null;
		}
		List<String> byLine = new ArrayList<>();
		byLine.add(null);
		for (String line : out) {
			// a header: <40 hex> <original line> <final line> [<lines in group>]
			if (line.length() > 41 && line.charAt(40) == ' ' && isHex(line, 40)) {
				String[] f = line.split(" ");
				int fin = Integer.parseInt(f[2]);
				while (byLine.size() <= fin) {
					byLine.add(null);
				}
				byLine.set(fin, f[0]);
			}
		}
		return byLine.toArray(new String[0]);
	}

	/** The growth of the given lines (a method's code lines) of a file's blame. */
	public Of of(String[] blame, int[] lines) {
		int added = 0;
		Set<String> commits = new HashSet<>();
		for (int l : lines) {
			String c = l > 0 && l < blame.length ? blame[l] : null;
			if (c != null && window.contains(c)) {
				added++;
				commits.add(c);
			}
		}
		return new Of(added, commits.size());
	}

	/** How many commits in the window touched any of the paths. */
	public int touching(Set<String> paths) {
		if (paths.isEmpty() || window.isEmpty()) {
			return 0;
		}
		List<String> out = git(root, "log", "--format=@%H", "--name-only", "--max-count=" + window.size(), "HEAD");
		if (out == null) {
			return 0;
		}
		int n = 0;
		String commit = null;
		boolean counted = false;
		for (String line : out) {
			if (line.startsWith("@")) {
				commit = line.substring(1);
				counted = false;
			} else if (!counted && commit != null && window.contains(commit) && paths.contains(line)) {
				n++;
				counted = true;
			}
		}
		return n;
	}

	private static boolean isHex(String s, int n) {
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
				return false;
			}
		}
		return true;
	}

	/** A git command's output lines, or null when it fails. */
	static List<String> git(Path root, String... args) {
		List<String> cmd = new ArrayList<>();
		cmd.add("git");
		cmd.add("-C");
		cmd.add(root.toString());
		cmd.addAll(List.of(args));
		try {
			Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			byte[] bytes;
			try (InputStream in = p.getInputStream()) {
				ByteArrayOutputStream b = new ByteArrayOutputStream();
				in.transferTo(b);
				bytes = b.toByteArray();
			}
			if (p.waitFor() != 0) {
				return null;
			}
			String text = new String(bytes, StandardCharsets.UTF_8);
			return text.isEmpty() ? List.of() : List.of(text.split("\n"));
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
