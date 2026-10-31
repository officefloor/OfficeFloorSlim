package net.officefloor.officefloorslim.graph;

/**
 * Derives the HTTP endpoint (method + URL path) that an OfficeFloor REST
 * configuration file maps to, from its location under an {@code officefloor/rest}
 * (or any {@code .../rest}) directory.
 *
 * <p>
 * Encoding (see {@code RestArchitectImpl.addRestServices}):
 * <ul>
 * <li>the item name is the path under the {@code rest} dir, minus extension;</li>
 * <li>split on the LAST {@code '.'}: the suffix (upper-cased) is the HTTP method,
 * the prefix is the URL path; no dot means a path-level config file (no
 * method);</li>
 * <li>a path component of {@code index} maps to {@code /};</li>
 * <li>directory nesting is path nesting.</li>
 * </ul>
 */
public final class RestFileName {

	public final String httpMethod; // null for a path-level config file
	public final String urlPath;

	private RestFileName(String httpMethod, String urlPath) {
		this.httpMethod = httpMethod;
		this.urlPath = urlPath;
	}

	public boolean isEndpoint() {
		return this.httpMethod != null;
	}

	public String endpointLabel() {
		return this.httpMethod + " " + this.urlPath;
	}

	/**
	 * @param path the file's path, with {@code /} separators (an IDE file's path, or one relative
	 *             to a repository's root)
	 * @return a {@link RestFileName} if the file sits under a {@code rest} dir, else {@code null}.
	 */
	public static RestFileName parse(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}
		// Walk up collecting path segments until a "rest" directory is found.
		String[] segments = path.split("/");
		StringBuilder relative = new StringBuilder(stripExtension(segments[segments.length - 1]));
		for (int i = segments.length - 2; i >= 0; i--) {
			if ("rest".equals(segments[i])) {
				return fromItemName(relative.toString());
			}
			relative.insert(0, segments[i] + "/");
		}
		return null;
	}

	/** Parses a REST item name (relative path, no extension) into method + path. */
	public static RestFileName fromItemName(String itemName) {
		// Separate the trailing ".METHOD" on the final path segment only.
		int lastSlash = itemName.lastIndexOf('/');
		String lastSegment = lastSlash < 0 ? itemName : itemName.substring(lastSlash + 1);
		String dirPart = lastSlash < 0 ? "" : itemName.substring(0, lastSlash + 1);

		String method = null;
		String pathSegment = lastSegment;
		int lastDot = lastSegment.lastIndexOf('.');
		if (lastDot > 0) {
			method = lastSegment.substring(lastDot + 1).toUpperCase();
			pathSegment = lastSegment.substring(0, lastDot);
		}

		String path = dirPart + pathSegment;
		// "index" maps to the root path.
		if ("index".equalsIgnoreCase(pathSegment)) {
			path = dirPart.isEmpty() ? "/" : "/" + dirPart.substring(0, dirPart.length() - 1);
		} else {
			path = "/" + path;
		}
		path = path.replace("//", "/");
		return new RestFileName(method, path);
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			String ext = name.substring(dot + 1).toLowerCase();
			if (ext.equals("yml") || ext.equals("yaml")) {
				return name.substring(0, dot);
			}
		}
		return name;
	}
}
