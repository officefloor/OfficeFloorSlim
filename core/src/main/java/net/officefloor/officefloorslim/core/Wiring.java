package net.officefloor.officefloorslim.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.officefloor.officefloorslim.core.WiringFile.Ref;

/** What a repository's OfficeFloor configuration wires: the classes and methods it makes functions. */
public final class Wiring {

	public static final Wiring NONE = new Wiring(List.of());

	private final List<WiringFile> files;

	/** Binary class name ({@code $} as {@code .}) to the files that wire every method of it. */
	private final Map<String, List<String>> classes = new LinkedHashMap<>();

	/** {@code class#method} to the files that wire that method. */
	private final Map<String, List<String>> methods = new LinkedHashMap<>();

	public Wiring(List<WiringFile> files) {
		this.files = List.copyOf(files);
		for (WiringFile f : files) {
			for (Ref r : f.refs()) {
				String c = normal(r.className());
				if (r.method() == null) {
					classes.computeIfAbsent(c, k -> new ArrayList<>()).add(f.path());
				} else {
					methods.computeIfAbsent(c + "#" + r.method(), k -> new ArrayList<>()).add(f.path());
				}
			}
		}
	}

	public List<WiringFile> files() {
		return files;
	}

	/** The files that wire the method by name, or null. */
	public List<String> wiringMethod(String className, String method) {
		return className == null ? null : methods.get(normal(className) + "#" + method);
	}

	/** The files that wire every method of the class, or null. */
	public List<String> wiringClass(String className) {
		return className == null ? null : classes.get(normal(className));
	}

	/** A nested class is written {@code Outer$Inner} or {@code Outer.Inner}: one key for both. */
	private static String normal(String className) {
		return className.trim().replace('$', '.');
	}
}
