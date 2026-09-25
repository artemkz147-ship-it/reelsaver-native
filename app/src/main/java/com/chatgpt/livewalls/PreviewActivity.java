package com.chatgpt.livewalls;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

public class PreviewActivity extends Activity {
    private int videoIndex;
    private VideoView videoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        videoIndex = Math.max(0, Math.min(2, getIntent().getIntExtra("video_index", 0)));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoView = new VideoView(this);
        videoView.setVideoURI(videoUri(videoIndex));
        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            try { mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); } catch (Exception ignored) {}
            videoView.start();
        });
        root.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(Color.WHITE);
        back.setTextSize(38);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(0x66000000, 50));
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.START);
        backLp.leftMargin = dp(16);
        backLp.topMargin = dp(20);
        root.addView(back, backLp);
        back.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(20));
        panel.setBackground(round(0xD908090B, 24));

        TextView label = new TextView(this);
        label.setText("Обои " + (videoIndex + 1));
        label.setTextColor(Color.WHITE);
        label.setTextSize(22);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("После нажатия Android откроет системное подтверждение установки.");
        hint.setTextColor(Color.rgb(188, 191, 197));
        hint.setTextSize(13);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(6);
        hintLp.bottomMargin = dp(14);
        panel.addView(hint, hintLp);

        TextView install = new TextView(this);
        install.setText("Установить живые обои");
        install.setTextColor(Color.BLACK);
        install.setTextSize(16);
        install.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        install.setGravity(Gravity.CENTER);
        install.setBackground(round(Color.WHITE, 50));
        panel.addView(install, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        install.setOnClickListener(v -> installWallpaper());

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        panelLp.leftMargin = dp(14);
        panelLp.rightMargin = dp(14);
        panelLp.bottomMargin = dp(18);
        root.addView(panel, panelLp);

        setContentView(root);
    }

    private void installWallpaper() {
        getSharedPreferences("wallpaper", MODE_PRIVATE)
                .edit().putInt("selected_video", videoIndex).apply();
        ComponentName component = new ComponentName(this, VideoWallpaperService.class);
        try {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
            startActivity(intent);
        } catch (Exception directFailed) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (Exception chooserFailed) {
                Toast.makeText(this, "Не удалось открыть выбор живых обоев", Toast.LENGTH_LONG).show();
            }
        }
    }

    private Uri videoUri(int index) {
        int res = index == 0 ? R.raw.wallpaper1 : (index == 1 ? R.raw.wallpaper2 : R.raw.wallpaper3);
        return Uri.parse("android.resource://" + getPackageName() + "/" + res);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoView != null && !videoView.isPlaying()) videoView.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) videoView.pause();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
