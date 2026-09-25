package com.chatgpt.livewalls;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

public class MainActivity extends Activity {
    private final VideoView[] previews = new VideoView[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8, 9, 11));
        getWindow().setNavigationBarColor(Color.rgb(8, 9, 11));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 9, 11));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Живые обои");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.START);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Выберите видео и установите его на экран телефона");
        subtitle.setTextColor(Color.rgb(170, 174, 181));
        subtitle.setTextSize(15);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(18);
        root.addView(subtitle, subLp);

        for (int i = 0; i < 3; i++) {
            root.addView(makeCard(i), cardParams());
        }

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        lp.bottomMargin = dp(16);
        return lp;
    }

    private View makeCard(final int index) {
        FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(20, 22, 26));
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        card.setElevation(dp(4));

        VideoView vv = new VideoView(this);
        previews[index] = vv;
        vv.setVideoURI(videoUri(index));
        vv.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            try { mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); } catch (Exception ignored) {}
            vv.start();
        });
        card.addView(vv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View shade = new View(this);
        GradientDrawable shadeBg = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB8000000, 0x00000000});
        shade.setBackground(shadeBg);
        FrameLayout.LayoutParams shadeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150), Gravity.BOTTOM);
        card.addView(shade, shadeLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(18), dp(12), dp(12), dp(12));

        TextView name = new TextView(this);
        name.setText("Обои " + (index + 1));
        name.setTextColor(Color.WHITE);
        name.setTextSize(19);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        info.addView(name, nameLp);

        TextView choose = new TextView(this);
        choose.setText("Открыть");
        choose.setTextColor(Color.BLACK);
        choose.setTextSize(15);
        choose.setGravity(Gravity.CENTER);
        choose.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(50));
        choose.setBackground(pill);
        info.addView(choose, new LinearLayout.LayoutParams(dp(104), dp(46)));

        FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        card.addView(info, infoLp);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
            intent.putExtra("video_index", index);
            startActivity(intent);
        });
        return card;
    }

    private Uri videoUri(int index) {
        int res = index == 0 ? R.raw.wallpaper1 : (index == 1 ? R.raw.wallpaper2 : R.raw.wallpaper3);
        return Uri.parse("android.resource://" + getPackageName() + "/" + res);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (VideoView vv : previews) if (vv != null && !vv.isPlaying()) vv.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (VideoView vv : previews) if (vv != null && vv.isPlaying()) vv.pause();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
