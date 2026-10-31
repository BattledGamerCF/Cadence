package dev.cadence.metal.record;

import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

/**
 * Record Mode's HUD, drawn by the CPU into an RGBA overlay (one word a pixel, alpha in the top byte) that every frame blends
 * over the world (record.metal), and drawn again only when it changes: the F3 text and chat at most 4 times a second, the
 * hotbar and bars when a value does. A frame's GPU work for the HUD is then one read a pixel (drawing the text in the frame
 * was ~11% of it). The most basic HUD: the game's font for the text over a dark box, each hotbar item its colour, the
 * selected slot outlined, health, food, XP and (underwater) air as bars. Render thread.
 */
final class RecordHud {
	/** The state the bars and hotbar show: nine slot colours (0 empty), selected slot, health, food (0..20), air (0..20, 255 not
	 * shown), XP progress (0..1000). */
	static final int STATE = 14;

	private static final int BOX = 0x8C000000, WHITE = 0xFFFFFFFF;  // (the text's box: black at 55%, as the game's F3)

	private final long ctx;
	/** Two overlays: frames read the front one while a redraw goes into the other (a redraw in place blanked the HUD). */
	private final long[] buffers = new long[2], addrs = new long[2];
	private int front;
	private long buffer, addr;  // (the one being drawn: buffers[1 - front] while drawing, then the front)
	private int w, h, cap;
	/** Smaller buffers replaced by bigger ones: released once no frame in flight can still read them. */
	private final long[] retired = new long[2];
	private long retireFrame;
	private byte[] font;  // ascii.png's alpha, 128 x 128 (16 x 16 glyphs of 8 x 8)
	private final int[] drawn = new int[STATE];
	private final String[] drawnText = new String[16];
	private int drawnScale;
	private boolean dirty = true;

	RecordHud(long ctx) {
		this.ctx = ctx;
	}

	void font(byte[] alpha) {
		this.font = alpha;
		this.dirty = true;
	}

	/**
	 * The overlay for this frame (the buffer to bind), drawn again if anything changed: `lines` the F3 block (top left), `chat`
	 * the chat lines (bottom left), `state` the bars' and hotbar's values.
	 */
	long frame(long frameNo, int w, int h, int scale, String[] lines, int lineCount, String[] chat, int chatCount, int[] state) {
		if (frameNo - this.retireFrame > 256) releaseRetired();
		if (w != this.w || h != this.h) {
			int need = w * h * 4;
			if (need > this.cap) {
				releaseRetired();
				for (int k = 0; k < 2; k++) {
					this.retired[k] = this.buffers[k];
					this.buffers[k] = RecordNative.buffer(this.ctx, need);
					this.addrs[k] = RecordNative.contents(this.buffers[k]);
				}
				this.retireFrame = frameNo;
				this.cap = need;
			}
			this.w = w;
			this.h = h;
			this.dirty = true;
		}
		if (!this.dirty && scale == this.drawnScale && Arrays.equals(state, 0, STATE, this.drawn, 0, STATE)
			&& same(lines, lineCount, 0) && same(chat, chatCount, 8)) return this.buffers[this.front];
		this.dirty = false;
		this.drawnScale = scale;
		System.arraycopy(state, 0, this.drawn, 0, STATE);
		Arrays.fill(this.drawnText, null);
		for (int i = 0; i < lineCount; i++) this.drawnText[i] = lines[i];
		for (int i = 0; i < chatCount; i++) this.drawnText[8 + i] = chat[i];
		int back = 1 - this.front;
		this.buffer = this.buffers[back];
		this.addr = this.addrs[back];
		draw(scale, lines, lineCount, chat, chatCount, state);
		this.front = back;
		return this.buffer;
	}

	private void releaseRetired() {
		for (int k = 0; k < 2; k++)
			if (this.retired[k] != 0) {
				RecordNative.releaseObject(this.retired[k]);
				this.retired[k] = 0;
			}
	}

	private boolean same(String[] text, int count, int at) {
		for (int i = 0; i < 8; i++) {
			String s = i < count ? text[i] : null;
			if (!java.util.Objects.equals(s, this.drawnText[at + i])) return false;
		}
		return true;
	}

	private void draw(int s, String[] lines, int lineCount, String[] chat, int chatCount, int[] st) {
		MemoryUtil.memSet(this.addr, 0, (long) this.w * this.h * 4);
		text(2 * s, 2 * s, s, lines, lineCount);
		if (chatCount > 0) text(2 * s, this.h - (24 + 10 * chatCount) * s, s, chat, chatCount);
		// the hotbar: nine slots, each its item's colour (an empty one darkened), the selected one outlined white
		int slot = 10 * s, barW = slot * 9;
		int x0 = this.w / 2 - barW / 2, y0 = this.h - slot - 2 * s;
		for (int k = 0; k < 9; k++)
			for (int py = 0; py < slot; py++)
				for (int lx = 0; lx < slot; lx++) {
					boolean edge = lx < s || lx >= slot - s || py < s || py >= slot - s;
					int item = st[k];
					int c = edge ? (k == st[9] ? WHITE : 0xFF404040) : item != 0 ? 0xFF000000 | rgba(item) : 0x80000000;
					put(x0 + k * slot + lx, y0 + py, c);
				}
		// the XP bar just above the hotbar
		for (int px = 0; px < barW; px++) put(x0 + px, y0 - s, px < barW * st[13] / 1000 ? 0xFF33FF80 : 0xFF262626);
		// health (left) and food (right) above it, 0..20; air above the food bar while it's not full
		int bh = 2 * s, halfW = barW / 2 - 2 * s, by = y0 - 4 * s, ay = y0 - 7 * s;
		for (int py = 0; py < bh; py++)
			for (int px = 0; px < halfW; px++) {
				put(x0 + px, by + py, px < halfW * st[10] / 20 ? 0xFF1A1AD9 : 0xFF333333);
				put(x0 + barW - halfW + px, by + py, px >= halfW - halfW * st[11] / 20 ? 0xFF3380BF : 0xFF333333);
				if (st[12] != 255) put(x0 + barW - halfW + px, ay + py, px >= halfW - halfW * st[12] / 20 ? 0xFFFFCC99 : 0xFF333333);
			}
	}

	/** Lines of text at (ox, oy): the game's font, 6-pixel advance, 10-pixel lines, a dark box behind each character. */
	private void text(int ox, int oy, int s, String[] lines, int count) {
		int adv = 6 * s, lineH = 10 * s;
		for (int i = 0; i < count; i++) {
			String line = lines[i];
			if (line == null) continue;
			int n = Math.min(64, line.length());
			for (int k = 0; k < n; k++) {
				char ch = line.charAt(k);
				int c = ch > 0 && ch < 256 ? ch : '?';
				int cx = ox + k * adv, cy = oy + i * lineH;
				for (int py = 0; py < lineH; py++)
					for (int px = 0; px < adv; px++) {
						int lx = px / s, ly = py / s;
						boolean ink = this.font != null && lx < 8 && ly >= 1 && ly < 9
							&& (this.font[((c >> 4) * 8 + ly - 1) * 128 + (c & 15) * 8 + lx] & 0xFF) > 127;
						put(cx + px, cy + py, ink ? WHITE : BOX);
					}
			}
		}
	}

	/** An 0xRRGGBB colour as the overlay's word (bytes r, g, b, a in memory: unpack_unorm4x8 order). */
	private static int rgba(int rgb) {
		return (rgb >> 16) & 0xFF | (rgb & 0xFF00) | (rgb & 0xFF) << 16;
	}

	private void put(int x, int y, int c) {
		if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
		MemoryUtil.memPutInt(this.addr + ((long) y * this.w + x) * 4, c);
	}

	/** (testing) The overlay's memory and size in bytes. */
	long address() {
		return this.addrs[this.front];
	}

	long bytes() {
		return (long) this.w * this.h * 4;
	}

	void release() {
		for (int k = 0; k < 2; k++) {
			if (this.buffers[k] != 0) RecordNative.releaseObject(this.buffers[k]);
			this.buffers[k] = 0;
		}
		releaseRetired();
	}
}
