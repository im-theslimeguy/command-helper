package se.miljo.beef;

/**
 * Short, user-visible AI failure text (HTTP code + API status name when known).
 */
public final class AiErrorSummary {

	private AiErrorSummary() {}

	public static String fromThrowable(Throwable ex) {
		Throwable t = unwrap(ex);
		String m = t.getMessage();
		if (m == null || m.isBlank()) {
			return t.getClass().getSimpleName();
		}
		if (m.length() > 120) {
			return m.substring(0, 117) + "...";
		}
		return m;
	}

	private static Throwable unwrap(Throwable ex) {
		Throwable t = ex;
		int guard = 0;
		while (t.getCause() != null && t.getCause() != t && guard++ < 8) {
			t = t.getCause();
		}
		return t;
	}
}
