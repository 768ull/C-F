package br.com.colecaocf;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Locale;

public final class LocalVideoPlayerActivity extends Activity {
    public static final String EXTRA_SLOT = "cfa.video.slot";
    public static final String EXTRA_LOCAL_PATH = "cfa.video.local_path";

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView speedButton;
    private boolean resumeAfterPause;
    private boolean wasPlayingBeforePause;
    private float playbackSpeed = 1.0f;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersive();

        int slot = Math.max(1, Math.min(3, getIntent().getIntExtra(EXTRA_SLOT, 1)));
        Uri source = resolveVideoUri(slot);
        if (source == null) {
            Toast.makeText(this, "Este vídeo não está disponível no C-F-A.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(true);
        playerView.setControllerShowTimeoutMs(4500);
        playerView.setKeepContentOnPlayerReset(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(5), dp(12), dp(5));
        topBar.setBackgroundColor(0x990D0E12);

        TextView back = new TextView(this);
        back.setText("‹  VÍDEOS");
        back.setTextColor(Color.WHITE);
        back.setTextSize(15);
        back.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(dp(8), 0, dp(16), 0);
        back.setContentDescription("Voltar à lista de vídeos");
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(0, dp(44), 1f));

        speedButton = new TextView(this);
        speedButton.setText("1,0×");
        speedButton.setTextColor(Color.WHITE);
        speedButton.setTextSize(14);
        speedButton.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        speedButton.setGravity(Gravity.CENTER);
        speedButton.setBackgroundColor(0x553A3D48);
        speedButton.setPadding(dp(14), 0, dp(14), 0);
        speedButton.setContentDescription("Alterar velocidade de reprodução");
        speedButton.setOnClickListener(this::showSpeedMenu);
        topBar.addView(speedButton, new LinearLayout.LayoutParams(-2, dp(40)));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP);
        topParams.leftMargin = dp(10);
        topParams.rightMargin = dp(10);
        topParams.topMargin = dp(10);
        root.addView(topBar, topParams);
        playerView.setControllerVisibilityListener(visibility -> topBar.setVisibility(visibility));

        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                updateOrientationFor(videoSize);
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                Toast.makeText(LocalVideoPlayerActivity.this,
                        "Não foi possível reproduzir este vídeo. Verifique se o MP4 está completo e compatível.",
                        Toast.LENGTH_LONG).show();
            }
        });

        player.setMediaItem(MediaItem.fromUri(source));
        player.prepare();
        player.play();
    }

    private Uri resolveVideoUri(int slot) {
        String localPath = getIntent().getStringExtra(EXTRA_LOCAL_PATH);
        if (localPath != null && !localPath.trim().isEmpty()) {
            File local = new File(localPath);
            if (local.isFile() && local.length() > 1024L) return Uri.fromFile(local);
        }
        String assetPath = "videos/video-" + slot + ".mp4";
        try (java.io.InputStream input = getAssets().open(assetPath)) {
            if (input.read() != -1) return Uri.parse("asset:///" + assetPath);
        } catch (Exception ignored) {
        }
        return null;
    }

    private void updateOrientationFor(VideoSize size) {
        if (size.width <= 0 || size.height <= 0) return;
        double width = size.width * (double) size.pixelWidthHeightRatio;
        double height = size.height;
        if (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270) {
            double tmp = width;
            width = height;
            height = tmp;
        }
        int requested = width >= height
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        if (getRequestedOrientation() != requested) setRequestedOrientation(requested);
    }

    private void showSpeedMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        float[] speeds = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        for (float speed : speeds) {
            String label = speed == 1.0f ? "Normal (1,0×)"
                    : String.format(Locale.forLanguageTag("pt-BR"), "%.2f×", speed).replace('.', ',');
            MenuItem item = menu.getMenu().add(label);
            item.setOnMenuItemClickListener(selected -> {
                playbackSpeed = speed;
                if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
                speedButton.setText(String.format(Locale.forLanguageTag("pt-BR"), "%.2f×", speed)
                        .replace('.', ',').replace(",00", ",0"));
                return true;
            });
        }
        menu.show();
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @Override
    protected void onPause() {
        if (player != null) {
            wasPlayingBeforePause = player.isPlaying();
            player.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        if (player != null && wasPlayingBeforePause) {
            wasPlayingBeforePause = false;
            player.play();
        }
    }

    @Override
    protected void onDestroy() {
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        finish();
    }
}
