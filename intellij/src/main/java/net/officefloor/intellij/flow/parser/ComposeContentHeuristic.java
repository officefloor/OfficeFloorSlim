package net.officefloor.intellij.flow.parser;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import com.intellij.openapi.vfs.VirtualFile;

/**
 * Fast, PSI-free check of whether a YAML file is (probably) an OfficeFloor
 * compose/activity configuration. Used by the editor provider's {@code accept}
 * so the Flow tab is not offered for unrelated YAML (CI pipelines, etc.).
 */
public final class ComposeContentHeuristic {

	private static final long MAX_BYTES = 256 * 1024;

	/** A compose-defining key at indentation level 1 (under a function) or a top-level {@code composition:}. */
	private static final Pattern COMPOSE_KEY = Pattern.compile(
			"(?m)^\\s+(class|source|procedure|next|outputs|escalations|govern)\\s*:" //
					+ "|^composition\\s*:");

	private ComposeContentHeuristic() {
	}

	/** True if the file lives under an {@code officefloor} directory. */
	public static boolean isUnderOfficeFloorDir(VirtualFile file) {
		for (VirtualFile dir = file.getParent(); dir != null; dir = dir.getParent()) {
			String name = dir.getName();
			if ("officefloor".equals(name)) {
				return true;
			}
		}
		return false;
	}

	public static boolean looksLikeCompose(VirtualFile file) {
		if (file == null || file.isDirectory() || file.getLength() > MAX_BYTES) {
			return false;
		}
		try {
			byte[] bytes = file.contentsToByteArray();
			String text = new String(bytes, StandardCharsets.UTF_8);
			return COMPOSE_KEY.matcher(text).find();
		} catch (Exception ex) {
			return false;
		}
	}

	public static boolean accepts(VirtualFile file) {
		String ext = file.getExtension();
		if (ext == null) {
			return false;
		}
		ext = ext.toLowerCase();
		if (!ext.equals("yml") && !ext.equals("yaml")) {
			return false;
		}
		return isUnderOfficeFloorDir(file) || looksLikeCompose(file);
	}
}
