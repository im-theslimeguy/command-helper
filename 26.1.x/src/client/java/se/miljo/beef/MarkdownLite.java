package se.miljo.beef;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Minimal Markdown → {@link Component} for chat bubbles (headings, lists, bold, italic, inline code).
 * Raw syntax is stripped; unsupported parts stay plain.
 */
public final class MarkdownLite {

	private MarkdownLite() {}

	public static Component toComponent(String raw) {
		if (raw == null || raw.isEmpty()) {
			return Component.literal("");
		}
		MutableComponent out = Component.literal("");
		String[] lines = raw.split("\n", -1);
		for (int li = 0; li < lines.length; li++) {
			if (li > 0) {
				out.append(Component.literal("\n"));
			}
			out.append(parseLine(lines[li]));
		}
		return out;
	}

	private static MutableComponent parseLine(String line) {
		String t = line.stripTrailing();
		if (t.startsWith("### ")) {
			return headingLine(t.substring(4), ChatFormatting.GOLD);
		}
		if (t.startsWith("## ")) {
			return headingLine(t.substring(3), ChatFormatting.YELLOW);
		}
		if (t.startsWith("# ") && !t.startsWith("##")) {
			return headingLine(t.substring(2), ChatFormatting.GOLD);
		}
		String list = t.startsWith("- ") ? t.substring(2)
			: (t.startsWith("* ") && !t.startsWith("**")) ? t.substring(2)
			: null;
		if (list != null) {
			MutableComponent m = Component.literal("\u2022 ");
			m.append(parseInline(list));
			return m;
		}
		if (t.matches("\\d+\\.\\s.*")) {
			int sp = t.indexOf(' ');
			MutableComponent m = Component.literal(t.substring(0, sp + 1));
			m.append(parseInline(t.substring(sp + 1)));
			return m;
		}
		return parseInline(t);
	}

	private static MutableComponent headingLine(String rest, ChatFormatting color) {
		return Component.literal(rest).withStyle(color).withStyle(ChatFormatting.BOLD);
	}

	private static MutableComponent parseInline(String s) {
		MutableComponent out = Component.literal("");
		int i = 0;
		final int n = s.length();
		while (i < n) {
			if (i + 1 < n && s.charAt(i) == '*' && s.charAt(i + 1) == '*') {
				int j = s.indexOf("**", i + 2);
				if (j < 0) {
					out.append(Component.literal(s.substring(i)));
					break;
				}
				out.append(Component.literal(s.substring(i + 2, j)).withStyle(ChatFormatting.BOLD));
				i = j + 2;
				continue;
			}
			if (s.charAt(i) == '`') {
				int j = s.indexOf('`', i + 1);
				if (j < 0) {
					out.append(Component.literal(s.substring(i)));
					break;
				}
				out.append(Component.literal(s.substring(i + 1, j))
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
				i = j + 1;
				continue;
			}
			if (s.charAt(i) == '*' || s.charAt(i) == '_') {
				char q = s.charAt(i);
				int j = s.indexOf(q, i + 1);
				if (j < 0) {
					out.append(Component.literal(String.valueOf(q)));
					i++;
					continue;
				}
				out.append(Component.literal(s.substring(i + 1, j)).withStyle(ChatFormatting.ITALIC));
				i = j + 1;
				continue;
			}
			int next = n;
			for (int k = i; k < n; k++) {
				char c = s.charAt(k);
				if (c == '*' || c == '`' || c == '_') {
					next = k;
					break;
				}
			}
			if (next <= i) {
				out.append(Component.literal(String.valueOf(s.charAt(i))));
				i++;
				continue;
			}
			out.append(Component.literal(s.substring(i, next)));
			i = next;
		}
		return out;
	}
}
