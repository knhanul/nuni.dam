package co.kr.nuni.dam;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.text.style.ImageSpan;
import android.view.*;
import android.widget.*;

public final class Ui {
  // Design tokens (Section 5.2)
  public static final int BG = 0xfff4f2ec,
      SURFACE = 0xffffffff,
      SURFACE_SUBTLE = 0xffe8ede5,
      INK = 0xff24332b,
      MUTED = 0xff626d65,
      PRIMARY = 0xff365b46,
      ON_PRIMARY = 0xffffffff,
      OUTLINE = 0xffcdd4c9,
      DANGER = 0xffa64932,
      DARK_BG = 0xff18221d,
      DARK_TEXT = 0xffedefe8,
      DARK_SECONDARY = 0xffb5bfb4;

  // Backwards-compatible aliases
  public static final int GREEN = PRIMARY,
      ACCENT = PRIMARY,
      ACCENT_DEEP = PRIMARY,
      BLUSH = SURFACE_SUBTLE,
      SOFT = SURFACE_SUBTLE,
      LINE = OUTLINE;

  private static final Typeface SERIF_BOLD = Typeface.create("serif", Typeface.BOLD);

  public static int dp(Context c, float n) {
    return Math.round(c.getResources().getDisplayMetrics().density * n);
  }

  public static int outerPaddingDp(int widthDp) {
    if (widthDp >= 840) return 32;
    if (widthDp >= 600) return 24;
    return 16;
  }

  public static GradientDrawable bg(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }

  public static GradientDrawable strokeBg(int fill, int stroke, int strokePx, int radius) {
    GradientDrawable d = bg(fill, radius);
    d.setStroke(strokePx, stroke);
    return d;
  }

  public static Drawable ripple(Drawable content, int color) {
    return new RippleDrawable(ColorStateList.valueOf(color), content, null);
  }

  public static Drawable icon(Context c, int res, int color, float size) {
    Drawable d = c.getDrawable(res).mutate();
    d.setTint(color);
    int px = dp(c, size);
    d.setBounds(0, 0, px, px);
    return d;
  }

  private static Drawable chip(Context c, int res, int iconColor, int chipColor) {
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor(chipColor);
    int size = dp(c, 36), pad = dp(c, 8);
    circle.setSize(size, size);
    LayerDrawable d = new LayerDrawable(new Drawable[] {circle, icon(c, res, iconColor, 20)});
    d.setLayerInset(1, pad, pad, pad, pad);
    d.setBounds(0, 0, size, size);
    return d;
  }

  public static TextView text(Context c, String s, float size, int color) {
    TextView v = new TextView(c);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setLineSpacing(dp(c, 3), 1);
    return v;
  }

  public static TextView title(Context c, String s, float size) {
    TextView v = text(c, s, size, INK);
    v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return v;
  }

  public static TextView serifTitle(Context c, String s, float size) {
    TextView v = text(c, s, size, INK);
    v.setTypeface(SERIF_BOLD);
    return v;
  }

  public static LinearLayout column(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  public static LinearLayout row(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  public static void gap(LinearLayout p, int h) {
    p.addView(new View(p.getContext()), new LinearLayout.LayoutParams(1, dp(p.getContext(), h)));
  }

  public static View divider(Context c) {
    View v = new View(c);
    v.setBackgroundColor(OUTLINE);
    return v;
  }

  public static LinearLayout card(LinearLayout parent) {
    Context c = parent.getContext();
    LinearLayout v = column(c);
    v.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20));
    v.setBackground(strokeBg(SURFACE, OUTLINE, dp(c, 1), dp(c, 18)));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(c, 16);
    parent.addView(v, p);
    return v;
  }

  public static void section(LinearLayout card, int icon, String title) {
    Context c = card.getContext();
    TextView t = title(c, title, 18);
    if (icon != 0) {
      t.setCompoundDrawablesRelative(chip(c, icon, PRIMARY, SURFACE_SUBTLE), null, null, null);
      t.setCompoundDrawablePadding(dp(c, 12));
    }
    t.setGravity(Gravity.CENTER_VERTICAL);
    card.addView(t);
    gap(card, 8);
  }

  private static Button base(Context c, String text, boolean primary, Runnable action) {
    Button b = new Button(c);
    b.setText(text);
    b.setTextSize(15);
    b.setAllCaps(false);
    b.setStateListAnimator(null);
    b.setMinWidth(dp(c, 48));
    b.setMinimumWidth(dp(c, 48));
    b.setMinHeight(dp(c, 48));
    b.setMinimumHeight(dp(c, 48));
    b.setTextColor(primary ? ON_PRIMARY : INK);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  public static Button button(Context c, String text, boolean primary, Runnable action) {
    return button(c, text, primary, 0, action);
  }

  public static Button button(Context c, String text, boolean primary, int icon, Runnable action) {
    Button b = base(c, text, primary, action);
    if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
    if (icon != 0) {
      SpannableString s = new SpannableString("  " + text);
      s.setSpan(
          new CenteredImageSpan(icon(c, icon, primary ? ON_PRIMARY : PRIMARY, 18)),
          0,
          1,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      b.setText(s);
    }
    b.setBackground(
        ripple(
            primary ? bg(PRIMARY, dp(c, 12)) : strokeBg(SURFACE_SUBTLE, OUTLINE, dp(c, 1), dp(c, 12)),
            primary ? 0x33ffffff : 0x22365b46));
    b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
    return b;
  }

  public static Button dangerButton(Context c, String text, int icon, Runnable action) {
    Button b = base(c, text, true, action);
    b.setTextColor(ON_PRIMARY);
    if (icon != 0) {
      SpannableString s = new SpannableString("  " + text);
      s.setSpan(
          new CenteredImageSpan(icon(c, icon, ON_PRIMARY, 18)),
          0,
          1,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      b.setText(s);
    }
    b.setBackground(ripple(bg(DANGER, dp(c, 12)), 0x33ffffff));
    b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
    return b;
  }

  public static void add(LinearLayout parent, String text, boolean primary, Runnable action) {
    add(parent, text, primary, 0, action);
  }

  public static void add(
      LinearLayout parent, String text, boolean primary, int icon, Runnable action) {
    Context c = parent.getContext();
    Button b = base(c, text, primary, action);
    b.setTextColor(primary ? ON_PRIMARY : INK);
    if (primary) b.setTypeface(Typeface.DEFAULT_BOLD);
    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    b.setCompoundDrawablesRelative(
        icon == 0
            ? null
            : chip(c, icon, primary ? ON_PRIMARY : PRIMARY, primary ? 0x26ffffff : SURFACE_SUBTLE),
        null,
        icon(c, R.drawable.ic_chevron, primary ? 0xccffffff : MUTED, 18),
        null);
    b.setCompoundDrawablePadding(dp(c, 12));
    b.setPadding(dp(c, icon == 0 ? 16 : 10), dp(c, 8), dp(c, 14), dp(c, 8));
    b.setBackground(
        ripple(
            primary ? bg(PRIMARY, dp(c, 12)) : strokeBg(SURFACE_SUBTLE, OUTLINE, dp(c, 1), dp(c, 12)),
            primary ? 0x33ffffff : 0x22365b46));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    b.setMinHeight(dp(c, primary ? 56 : 52));
    p.topMargin = dp(c, 8);
    parent.addView(b, p);
  }

  /** Clean setting/summary row with title, subtitle/summary, and click action. */
  public static LinearLayout settingRow(
      LinearLayout parent, int iconRes, String title, String summary, Runnable onClick) {
    Context c = parent.getContext();
    LinearLayout r = row(c);
    r.setMinimumHeight(dp(c, 56));
    r.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
    r.setBackground(ripple(bg(Color.TRANSPARENT, dp(c, 12)), 0x1f365b46));
    r.setClickable(true);
    r.setFocusable(true);
    r.setContentDescription(summary == null || summary.isEmpty() ? title : title + ", " + summary);
    r.setOnClickListener(v -> onClick.run());
    if (iconRes != 0) {
      ImageView iv = new ImageView(c);
      iv.setImageDrawable(chip(c, iconRes, PRIMARY, SURFACE_SUBTLE));
      iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 36));
      ip.setMarginEnd(dp(c, 12));
      r.addView(iv, ip);
    }
    LinearLayout col = column(c);
    TextView tvTitle = text(c, title, 16, INK);
    tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
    col.addView(tvTitle);
    if (summary != null && !summary.isEmpty()) {
      TextView tvSum = text(c, summary, 14, MUTED);
      col.addView(tvSum);
    }
    r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView chev = new ImageView(c);
    chev.setImageDrawable(icon(c, R.drawable.ic_chevron, MUTED, 18));
    chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    r.addView(chev, new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
    parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
    return r;
  }

  public static Button tile(Context c, String text, int icon, int color, Runnable action) {
    Button b = base(c, text, false, action);
    b.setTextSize(13f);
    b.setTextColor(color);
    b.setBackground(ripple(bg(0x00000000, dp(c, 12)), 0x22365b46));
    b.setPadding(dp(c, 6), dp(c, 8), dp(c, 6), dp(c, 8));
    b.setCompoundDrawablePadding(dp(c, 4));
    tileIcon(b, icon, color);
    return b;
  }

  public static void tileIcon(Button b, int icon, int color) {
    b.setCompoundDrawablesRelative(null, icon(b.getContext(), icon, color, 22), null, null);
  }

  public static LinearLayout page(Activity a, String title, String sub) {
    a.getWindow().setStatusBarColor(BG);
    a.getWindow().setNavigationBarColor(BG);
    ScrollView scroll = new ScrollView(a);
    scroll.setBackgroundColor(BG);
    scroll.setFillViewport(true);
    int widthDp = a.getResources().getConfiguration().screenWidthDp;
    int pad = dp(a, outerPaddingDp(widthDp));
    LinearLayout root = column(a);
    root.setPadding(pad, dp(a, 20), pad, dp(a, 28));
    scroll.addView(root);
    a.setContentView(scroll);
    scroll.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              i.getSystemWindowInsetLeft(),
              i.getSystemWindowInsetTop(),
              i.getSystemWindowInsetRight(),
              i.getSystemWindowInsetBottom());
          return i;
        });
    scroll.requestApplyInsets();
    header(a, root, title, sub);
    gap(root, 20);
    return root;
  }

  public static TextView header(Context a, LinearLayout root, String title, String sub) {
    LinearLayout brandRow = row(a);
    ImageView logo = new ImageView(a);
    logo.setImageResource(R.drawable.ic_nuni);
    logo.setBackground(bg(SURFACE_SUBTLE, dp(a, 8)));
    logo.setClipToOutline(true);
    logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    brandRow.addView(logo, new LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)));
    TextView brand = text(a, "nuni dam", 14, PRIMARY);
    brand.setLetterSpacing(.14f);
    brand.setPadding(dp(a, 10), 0, 0, 0);
    brandRow.addView(brand);
    root.addView(brandRow);
    gap(root, 12);
    TextView heading = title(a, title, 26);
    root.addView(heading);
    if (sub != null && !sub.isEmpty()) {
      gap(root, 4);
      root.addView(text(a, sub, 14, MUTED));
    }
    return heading;
  }

  public static EditText input(Context c, String hint, String value) {
    EditText e = new EditText(c);
    e.setHint(hint);
    e.setText(value);
    e.setTextSize(16);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setBackground(strokeBg(SURFACE, OUTLINE, dp(c, 1), dp(c, 12)));
    e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
    return e;
  }

  public static void toast(Context c, String text) {
    Toast.makeText(c, text, Toast.LENGTH_LONG).show();
  }

  public static boolean alive(Activity a) {
    return !a.isFinishing() && !a.isDestroyed();
  }

  public static void error(Activity a, Exception e) {
    if (alive(a))
      new AlertDialog.Builder(a)
          .setTitle("확인이 필요해요")
          .setMessage(e.getMessage() == null ? "작업을 완료하지 못했습니다." : e.getMessage())
          .setPositiveButton("확인", null)
          .show();
  }

  public static void open(Context c, Class<? extends Activity> cls) {
    c.startActivity(new Intent(c, cls));
  }

  public static void immersive(Activity a) {
    a.getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  private static final class CenteredImageSpan extends ImageSpan {
    CenteredImageSpan(Drawable d) {
      super(d);
    }

    @Override
    public void draw(
        Canvas canvas,
        CharSequence text,
        int start,
        int end,
        float x,
        int top,
        int y,
        int bottom,
        Paint paint) {
      Drawable d = getDrawable();
      Paint.FontMetricsInt fm = paint.getFontMetricsInt();
      int shift = y + (fm.descent + fm.ascent) / 2 - d.getBounds().height() / 2;
      canvas.save();
      canvas.translate(x, shift);
      d.draw(canvas);
      canvas.restore();
    }
  }
}
