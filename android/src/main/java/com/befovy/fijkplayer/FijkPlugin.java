package com.befovy.fijkplayer;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * FijkPlugin
 */
public class FijkPlugin
    implements MethodChannel.MethodCallHandler,
               FlutterPlugin,
               ActivityAware,
               FijkEngine,
               FijkVolume.VolumeKeyListener,
               AudioManager.OnAudioFocusChangeListener {

  // volume UI modes
  private static final int NO_UI_IF_PLAYABLE = 0;
  private static final int NO_UI_IF_PLAYING  = 1;
  @SuppressWarnings("unused")
  private static final int NEVER_SHOW_UI     = 2;
  private static final int ALWAYS_SHOW_UI    = 3;

  private final SparseArray<FijkPlayer> fijkPlayers = new SparseArray<>();
  private final QueuingEventSink mEventSink           = new QueuingEventSink();

  private FlutterPluginBinding mBinding;
  private WeakReference<Activity> mActivity;
  private WeakReference<Context>  mContext;

  private EventChannel mEventChannel;
  private Object       mAudioFocusRequest;
  private boolean      mAudioFocusRequested = false;
  private int          playableCnt          = 0;
  private int          playingCnt           = 0;
  private int          volumeUIMode         = ALWAYS_SHOW_UI;
  private float        volStep              = 1.0f / 16.0f;
  private boolean      eventListening       = false;

  // --- FlutterPlugin -------------------------------------------------------------------------

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    mBinding = binding;
    mContext = new WeakReference<>(binding.getApplicationContext());

    MethodChannel channel =
        new MethodChannel(binding.getBinaryMessenger(), "befovy.com/fijk");
    channel.setMethodCallHandler(this);

    mEventChannel = new EventChannel(binding.getBinaryMessenger(), "befovy.com/fijk/event");
    mEventChannel.setStreamHandler(new EventChannel.StreamHandler() {
      @Override
      public void onListen(Object o, EventChannel.EventSink eventSink) {
        mEventSink.setDelegate(eventSink);
      }
      @Override
      public void onCancel(Object o) {
        mEventSink.setDelegate(null);
      }
    });

    // Warm-up dummy player for surface registry
    FijkPlayer dummy = new FijkPlayer(this, true);
    dummy.setupSurface();
    dummy.release();

    AudioManager audioManager = audioManager();
    if (audioManager != null) {
      int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      volStep = Math.max(1.0f / (float) max, volStep);
    }
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    mBinding = null;
    mContext = null;
  }

  // --- ActivityAware ------------------------------------------------------------------------

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    mActivity = new WeakReference<>(binding.getActivity());
    if (binding.getActivity() instanceof FijkVolume.CanListenVolumeKey) {
      ((FijkVolume.CanListenVolumeKey) binding.getActivity())
          .setVolumeKeyListener(this);
    }
  }

  @Override public void onDetachedFromActivityForConfigChanges() { mActivity = null; }
  @Override public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding b) {
    onAttachedToActivity(b);
  }
  @Override public void onDetachedFromActivity() { mActivity = null; }

  // --- FijkEngine (surface & messenger providers) -------------------------------------------

  @Override
  @Nullable
  public TextureRegistry.SurfaceTextureEntry createSurfaceEntry() {
    return mBinding == null
        ? null
        : mBinding.getTextureRegistry().createSurfaceTexture();
  }

  @Override
  @Nullable
  public BinaryMessenger messenger() {
    return mBinding == null
        ? null
        : mBinding.getBinaryMessenger();
  }

  @Override
  @Nullable
  public Context context() {
    return mContext == null
        ? null
        : mContext.get();
  }

  @Override
  @Nullable
  public String lookupKeyForAsset(@NonNull String asset, @Nullable String pkg) {
    if (mBinding == null) return null;
    if (TextUtils.isEmpty(pkg)) {
      return mBinding.getFlutterAssets().getAssetFilePathByName(asset);
    } else {
      return mBinding.getFlutterAssets().getAssetFilePathByName(asset, pkg);
    }
  }

  // --- MethodCallHandler --------------------------------------------------------------------

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    Activity activity;
    switch (call.method) {
      case "getPlatformVersion":
        result.success("Android " + Build.VERSION.RELEASE);
        break;

      case "init":
        Log.i("FLUTTER", "call init:" + call.arguments);
        result.success(null);
        break;

      case "createPlayer":
        FijkPlayer player = new FijkPlayer(this, false);
        int pid = player.getPlayerId();
        fijkPlayers.append(pid, player);
        result.success(pid);
        break;

      case "releasePlayer":
        Integer argPid = call.argument("pid");
        if (argPid != null) {
          FijkPlayer p = fijkPlayers.get(argPid);
          if (p != null) {
            p.release();
            fijkPlayers.delete(argPid);
          }
        }
        result.success(null);
        break;

      case "logLevel":
        Integer lvl = call.argument("level");
        int level = (lvl == null ? 500 : lvl) / 100;
        level = Math.max(0, Math.min(level, 8));
        IjkMediaPlayer.loadLibrariesOnce(null);
        IjkMediaPlayer.setLogLevel(level);
        result.success(null);
        break;

      case "setOrientationPortrait":
        boolean changedPort = false;
        activity = mActivity != null ? mActivity.get() : null;
        if (activity != null &&
            activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
          } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
          }
          changedPort = true;
        }
        result.success(changedPort);
        break;

      case "setOrientationLandscape":
        boolean changedLand = false;
        activity = mActivity != null ? mActivity.get() : null;
        if (activity != null &&
            activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
          } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
          }
          changedLand = true;
        }
        result.success(changedLand);
        break;

      case "setOrientationAuto":
        activity = mActivity != null ? mActivity.get() : null;
        if (activity != null) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
          } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
          }
        }
        result.success(null);
        break;

      case "setScreenOn":
        Boolean on = call.argument("on");
        setScreenOn(on != null && on);
        result.success(null);
        break;

      case "isScreenKeptOn":
        result.success(isScreenKeptOn());
        break;

      case "brightness":
        result.success(getScreenBrightness());
        break;

      case "setBrightness":
        Double b = call.argument("brightness");
        if (b != null) setScreenBrightness(b.floatValue());
        result.success(null);
        break;

      case "requestAudioFocus":
        audioFocus(true);
        result.success(null);
        break;

      case "releaseAudioFocus":
        audioFocus(false);
        result.success(null);
        break;

      case "volumeDown":
        Double stepDown = call.argument("step");
        result.success(volumeDown(stepDown == null ? volStep : stepDown.floatValue()));
        break;

      case "volumeUp":
        Double stepUp = call.argument("step");
        result.success(volumeUp(stepUp == null ? volStep : stepUp.floatValue()));
        break;

      case "volumeMute":
        result.success(volumeMute());
        break;

      case "systemVolume":
        result.success(systemVolume());
        break;

      case "volumeSet":
        Double vv = call.argument("vol");
        result.success(vv == null ? systemVolume() : setSystemVolume(vv.floatValue()));
        break;

      case "volUiMode":
        Integer mode = call.argument("mode");
        if (mode != null) volumeUIMode = mode;
        result.success(null);
        break;

      case "onLoad":
        eventListening = true;
        result.success(null);
        break;

      case "onUnload":
        eventListening = false;
        result.success(null);
        break;

      default:
        Log.w("FLUTTER", "onMethodCall: " + call.method);
        result.notImplemented();
        break;
    }
  }

  // --- FijkEngine callbacks ----------------------------------------------------------------

  @Override
  public void onPlayingChange(int delta) {
    playingCnt += delta;
  }

  @Override
  public void onPlayableChange(int delta) {
    playableCnt += delta;
  }

  // --- Volume-key listener ------------------------------------------------------------------

  @Override
  public boolean onVolumeKeyDown(int keyCode, KeyEvent event) {
    switch (keyCode) {
      case KeyEvent.KEYCODE_VOLUME_DOWN:
        volumeDown(volStep);
        return true;
      case KeyEvent.KEYCODE_VOLUME_UP:
        volumeUp(volStep);
        return true;
      case KeyEvent.KEYCODE_VOLUME_MUTE:
        volumeMute();
        return true;
      default:
        return false;
    }
  }

  // --- Audio focus & screen utilities ------------------------------------------------------

  @Override
  public void onAudioFocusChange(int focusChange) {
    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
      mAudioFocusRequested = false;
      mAudioFocusRequest   = null;
    }
    Log.i("FIJKPLAYER", "onAudioFocusChange: " + focusChange);
  }

  @Override
  public void setScreenOn(boolean on) {
    Activity activity = mActivity != null ? mActivity.get() : null;
    if (activity == null) return;
    if (on) {
      activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } else {
      activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  private boolean isScreenKeptOn() {
    Activity activity = mActivity != null ? mActivity.get() : null;
    if (activity == null) return false;
    int flags = activity.getWindow().getAttributes().flags;
    return (flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
  }

  private float getScreenBrightness() {
    Activity activity = mActivity != null ? mActivity.get() : null;
    if (activity == null) return 0f;
    float brightness = activity.getWindow().getAttributes().screenBrightness;
    if (brightness < 0) {
      Context ctx = mContext != null ? mContext.get() : null;
      try {
        if (ctx != null) {
          brightness = Settings.System.getInt(
              ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        }
      } catch (Settings.SettingNotFoundException ignored) { }
    }
    return brightness;
  }

  private void setScreenBrightness(float brightness) {
    Activity activity = mActivity != null ? mActivity.get() : null;
    if (activity == null) return;
    WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
    lp.screenBrightness = brightness;
    activity.getWindow().setAttributes(lp);
  }

  @TargetApi(26)
  private void requestAudioFocus() {
    AudioManager am = audioManager();
    if (am == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      AudioAttributes attrs = new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
          .build();
      AudioFocusRequest afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(attrs)
          .setAcceptsDelayedFocusGain(true)
          .setOnAudioFocusChangeListener(this)
          .build();
      mAudioFocusRequest = afr;
      am.requestAudioFocus(afr);
    } else {
      am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }
    mAudioFocusRequested = true;
  }

  @TargetApi(26)
  private void abandonAudioFocus() {
    AudioManager am = audioManager();
    if (am == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mAudioFocusRequest instanceof AudioFocusRequest) {
      am.abandonAudioFocusRequest((AudioFocusRequest) mAudioFocusRequest);
    } else {
      am.abandonAudioFocus(this);
    }
    mAudioFocusRequested = false;
  }

  @Override
  public void audioFocus(boolean request) {
    Log.i("FIJKPLAYER", "audioFocus " + (request ? "request" : "release")
        + " state:" + mAudioFocusRequested);
    if (request && !mAudioFocusRequested) {
      requestAudioFocus();
    } else if (mAudioFocusRequested) {
      abandonAudioFocus();
    }
  }

  @Nullable
  private AudioManager audioManager() {
    Context ctx = mContext != null ? mContext.get() : null;
    if (ctx == null) {
      Log.e("FIJKPLAYER", "context null, can't get AudioManager");
      return null;
    }
    return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
  }

  private float systemVolume() {
    AudioManager am = audioManager();
    if (am == null) return 0f;
    float max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    float vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
    return vol / max;
  }

  private void sendVolumeEvent() {
    if (!eventListening) return;
    int flag = getVolumeChangeFlag();
    boolean showUi = (flag & AudioManager.FLAG_SHOW_UI) != 0;
    Map<String, Object> evt = new HashMap<>();
    evt.put("event", "volume");
    evt.put("sui", showUi);
    evt.put("vol", systemVolume());
    mEventSink.success(evt);
  }

  private float volumeUp(float step) {
    float vol = systemVolume() + step;
    return setSystemVolume(vol);
  }

  private float volumeDown(float step) {
    float vol = systemVolume() - step;
    return setSystemVolume(vol);
  }

  private float volumeMute() {
    setSystemVolume(0f);
    return 0f;
  }

  private int getVolumeChangeFlag() {
    if (volumeUIMode == ALWAYS_SHOW_UI) {
      return AudioManager.FLAG_SHOW_UI;
    } else if (volumeUIMode == NO_UI_IF_PLAYING && playingCnt == 0) {
      return AudioManager.FLAG_SHOW_UI;
    } else if (volumeUIMode == NO_UI_IF_PLAYABLE && playableCnt == 0) {
      return AudioManager.FLAG_SHOW_UI;
    }
    return 0;
  }

  private float setSystemVolume(float targetVol) {
    AudioManager am = audioManager();
    if (am == null) return targetVol;
    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    int idx = Math.max(0, Math.min((int) (targetVol * max), max));
    am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, getVolumeChangeFlag());
    sendVolumeEvent();
    return (float) idx / max;
  }
}
