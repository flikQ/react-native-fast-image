package com.dylanvann.fastimage;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import static com.dylanvann.fastimage.FastImageRequestListener.REACT_ON_ERROR_EVENT;
import static com.dylanvann.fastimage.FastImageRequestListener.REACT_ON_LOAD_END_EVENT;
import static com.dylanvann.fastimage.FastImageRequestListener.REACT_ON_LOAD_EVENT;

class FastImageViewManager extends SimpleViewManager<FastImageViewWithUrl> implements FastImageProgressListener {

    private static final String REACT_CLASS = "FastImageView";
    private static final String REACT_ON_LOAD_START_EVENT = "onFastImageLoadStart";
    private static final String REACT_ON_PROGRESS_EVENT = "onFastImageProgress";
    private static final Map<String, List<FastImageViewWithUrl>> VIEWS_FOR_URLS = new WeakHashMap<>();

    @Nullable
    private RequestManager requestManager = null;

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    protected FastImageViewWithUrl createViewInstance(ThemedReactContext reactContext) {
        if (isValidContextForGlide(reactContext)) {
            requestManager = Glide.with(reactContext);
        }

        return new FastImageViewWithUrl(reactContext);
    }

    @ReactProp(name = "source")
    public void setSrc(final FastImageViewWithUrl view, @Nullable ReadableMap source) {
        if (source == null || !source.hasKey("uri") || isNullOrEmpty(source.getString("uri"))) {
            // Cancel existing requests.
            if (requestManager != null) {
                requestManager.clear(view);
            }

            if (view.glideUrl != null) {
                FastImageOkHttpProgressGlideModule.forget(view.glideUrl.toStringUrl());
            }
            // Clear the image.
            view.setImageDrawable(null);
            return;
        }

        //final GlideUrl glideUrl = FastImageViewConverter.getGlideUrl(view.getContext(), source);
        final FastImageSource imageSource = FastImageViewConverter.getImageSource(view.getContext(), source);
        final GlideUrl glideUrl = imageSource.getGlideUrl();

        // Cancel existing request.
        view.glideUrl = glideUrl;
        if (requestManager != null) {
            requestManager.clear(view);
        }

        String key = glideUrl.toStringUrl();
        FastImageOkHttpProgressGlideModule.expect(key, this);
        List<FastImageViewWithUrl> viewsForKey = VIEWS_FOR_URLS.get(key);
        if (viewsForKey != null && !viewsForKey.contains(view)) {
            viewsForKey.add(view);
        } else if (viewsForKey == null) {
            List<FastImageViewWithUrl> newViewsForKeys = new ArrayList<>(Collections.singletonList(view));
            VIEWS_FOR_URLS.put(key, newViewsForKeys);
        }

        ThemedReactContext context = (ThemedReactContext) view.getContext();
        RCTEventEmitter eventEmitter = context.getJSModule(RCTEventEmitter.class);
        final int viewId = view.getId();
        eventEmitter.receiveEvent(viewId, REACT_ON_LOAD_START_EVENT, new WritableNativeMap());

        if (requestManager != null) {
            requestManager
                    // This will make this work for remote and local images. e.g.
                    //    - file:///
                    //    - content://
                    //    - res:/
                    //    - android.resource://
                    //    - data:image/png;base64
                    .load(imageSource.getSourceForLoad())
                    .apply(FastImageViewConverter.getOptions(context, imageSource, source))
                    .listener(new FastImageRequestListener(key))
                    .into(new SimpleTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource, @android.support.annotation.Nullable Transition<? super Drawable> transition) {
                            if (resource instanceof BitmapDrawable) {
                                Bitmap bitmap = ((BitmapDrawable) resource).getBitmap();
                                ThemedReactContext context = (ThemedReactContext) view.getContext();
                                RCTEventEmitter eventEmitter = context.getJSModule(RCTEventEmitter.class);
                                WritableMap resourceData = new WritableNativeMap();
                                resourceData.putInt("width", bitmap.getWidth());
                                resourceData.putInt("height", bitmap.getHeight());
                                eventEmitter.receiveEvent(viewId, REACT_ON_LOAD_EVENT, resourceData);
                                view.setImageBitmap(bitmap);

                            } else {
                                view.setImageDrawable(resource);
                            }

                        }
                    });
        }
    }

    @ReactProp(name = "tintColor", customType = "Color")
    public void setTintColor(FastImageViewWithUrl view, @Nullable Integer color) {
        if (color == null) {
            view.clearColorFilter();
        } else {
            view.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    @ReactProp(name = "resizeMode")
    public void setResizeMode(FastImageViewWithUrl view, String resizeMode) {
        final FastImageViewWithUrl.ScaleType scaleType = FastImageViewConverter.getScaleType(resizeMode);
        view.setScaleType(scaleType);
    }

    @Override
    public void onDropViewInstance(FastImageViewWithUrl view) {
        // This will cancel existing requests.
        if (requestManager != null) {
            requestManager.clear(view);
        }

        if (view.glideUrl != null) {
            final String key = view.glideUrl.toString();
            FastImageOkHttpProgressGlideModule.forget(key);
            List<FastImageViewWithUrl> viewsForKey = VIEWS_FOR_URLS.get(key);
            if (viewsForKey != null) {
                viewsForKey.remove(view);
                if (viewsForKey.size() == 0) VIEWS_FOR_URLS.remove(key);
            }
        }

        super.onDropViewInstance(view);
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.<String, Object>builder()
                .put(REACT_ON_LOAD_START_EVENT, MapBuilder.of("registrationName", REACT_ON_LOAD_START_EVENT))
                .put(REACT_ON_PROGRESS_EVENT, MapBuilder.of("registrationName", REACT_ON_PROGRESS_EVENT))
                .put(REACT_ON_LOAD_EVENT, MapBuilder.of("registrationName", REACT_ON_LOAD_EVENT))
                .put(REACT_ON_ERROR_EVENT, MapBuilder.of("registrationName", REACT_ON_ERROR_EVENT))
                .put(REACT_ON_LOAD_END_EVENT, MapBuilder.of("registrationName", REACT_ON_LOAD_END_EVENT))
                .build();
    }

    @Override
    public void onProgress(String key, long bytesRead, long expectedLength) {
        List<FastImageViewWithUrl> viewsForKey = VIEWS_FOR_URLS.get(key);
        if (viewsForKey != null) {
            for (FastImageViewWithUrl view : viewsForKey) {
                WritableMap event = new WritableNativeMap();
                event.putInt("loaded", (int) bytesRead);
                event.putInt("total", (int) expectedLength);
                ThemedReactContext context = (ThemedReactContext) view.getContext();
                RCTEventEmitter eventEmitter = context.getJSModule(RCTEventEmitter.class);
                int viewId = view.getId();
                eventEmitter.receiveEvent(viewId, REACT_ON_PROGRESS_EVENT, event);
            }
        }
    }

    @Override
    public float getGranularityPercentage() {
        return 0.5f;
    }

    private boolean isNullOrEmpty(final String url) {
        return url == null || url.trim().isEmpty();
    }


    private static boolean isValidContextForGlide(final Context context) {
        if (context == null) {
            return false;
        }
        if (context instanceof Activity) {
            final Activity activity = (Activity) context;
            if (isActivityDestroyed(activity)) {
                return false;
            }
        }

        if (context instanceof ThemedReactContext) {
            final Context baseContext = ((ThemedReactContext) context).getBaseContext();
            if (baseContext instanceof Activity) {
                final Activity baseActivity = (Activity) baseContext;
                return !isActivityDestroyed(baseActivity);
            }
        }

        return true;
    }

    private static boolean isActivityDestroyed(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return activity.isDestroyed() || activity.isFinishing();
        } else {
            return activity.isFinishing() || activity.isChangingConfigurations();
        }

    }
}
