package xtr.keymapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.nambimobile.widgets.efab.ExpandableFabLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import xtr.keymapper.databinding.CrosshairBinding;
import xtr.keymapper.databinding.Dpad1Binding;
import xtr.keymapper.databinding.Dpad2Binding;
import xtr.keymapper.databinding.KeymapEditorBinding;
import xtr.keymapper.databinding.ResizableBinding;
import xtr.keymapper.dpad.Dpad;
import xtr.keymapper.dpad.Dpad.DpadType;
import xtr.keymapper.floatingkeys.MovableFloatingActionKey;
import xtr.keymapper.floatingkeys.MovableFrameLayout;
import xtr.keymapper.mouse.MouseAimConfig;
import xtr.keymapper.mouse.MouseAimSettings;
import xtr.keymapper.server.InputService;

public class EditorUI extends OnKeyEventListener.Stub {

    private final WindowManager.LayoutParams mParams;
    private final WindowManager mWindowManager;
    private final LayoutInflater layoutInflater;
    private final ExpandableFabLayout mainView;

    private MovableFloatingActionKey keyInFocus;
    // Keyboard keys
    private final List<MovableFloatingActionKey> keyList = new ArrayList<>();
    private MovableFloatingActionKey leftClick;

    private MovableFrameLayout dpad1, dpad2, crosshair;
    private MouseAimConfig mouseAimConfig;
    // Default position of new views added
    private static final Float DEFAULT_X = 200f, DEFAULT_Y = 200f;
    private final KeymapEditorBinding binding;
    private final Context context;
    private final OnHideListener onHideListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final KeymapConfig keymapConfig;

    public EditorUI (Context context, OnHideListener l) {
        this.context = context;
        this.onHideListener = l;
        keymapConfig = new KeymapConfig(context);

        layoutInflater = context.getSystemService(LayoutInflater.class);
        mWindowManager = context.getSystemService(WindowManager.class);
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mParams.gravity = Gravity.CENTER;

        binding = KeymapEditorBinding.inflate(layoutInflater);
        mainView = binding.getRoot();
        setupButtons();
    }

    public void open() {
        try {
            loadKeymap();
        } catch (IOException e) {
            Log.d("EditorUI", e.toString());
        }
        if (mainView.getWindowToken() == null)
            if (mainView.getParent() == null)
                mWindowManager.addView(mainView, mParams);

        if (!onHideListener.getEvent()) {
            mainView.setOnKeyListener(this::onKey);
            mainView.setFocusable(true);
        }
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (keyInFocus != null) {
            String key = String.valueOf(event.getDisplayLabel());
            if ( key.matches("[a-zA-Z0-9]+" )) {
                keyInFocus.setText(key);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onKeyEvent(String event) {
        // line: /dev/input/event3 EV_KEY KEY_X DOWN
        String[] input_event = event.split("\\s+");
        String code = input_event[2];

        // Ignore non key events
        if(!input_event[1].equals("EV_KEY") || !code.contains("KEY_")) return;

        // Incoming calls are not guaranteed to be executed on the main thread
        mHandler.post(() -> {
            if (keyInFocus != null)
                keyInFocus.setText(input_event[2].substring(4));
        });
    }

    @Override
    public IBinder asBinder() {
        return this;
    }


    public interface OnHideListener {
        void onHideView();
        boolean getEvent();
    }

    public void hideView() {
        try {
            saveKeymap();
            mWindowManager.removeView(mainView);
            ((ViewGroup) mainView.getParent()).removeAllViews();
            mainView.invalidate();
            onHideListener.onHideView();
        } catch (Exception e) {
            Log.d("Error2", e.toString());
        }
    }

    private void loadKeymap() throws IOException {
        KeymapProfiles.Profile profile = new KeymapProfiles(context).getProfile(keymapConfig.profile);
        // Add Keyboard keys as Views
        profile.keys.forEach(this::addKey);

        if (profile.dpad1 != null) addDpad1(profile.dpad1.getX(), profile.dpad1.getY());

        if (profile.dpad2 != null) addDpad2(profile.dpad2.getX(), profile.dpad2.getY());

        mouseAimConfig = profile.mouseAimConfig;
        if (mouseAimConfig != null) addCrosshair(mouseAimConfig.xCenter, mouseAimConfig.yCenter);
    }

    private void saveKeymap() {
        ArrayList<String> linesToWrite = new ArrayList<>();

        if (dpad1 != null) {
            Dpad dpad = new Dpad(dpad1, DpadType.UDLR);
            linesToWrite.add(dpad.getData());
        }

        if (dpad2 != null) {
            Dpad dpad = new Dpad(dpad2, DpadType.WASD);
            linesToWrite.add(dpad.getData());

            // If WASD keys already added, remove them
            for (int i = 0; i < keyList.size(); i++)
                if (keyList.get(i).getText().matches("[WASD]"))
                    keyList.get(i).key = null;
        }

        if (crosshair != null) {
            // Get x and y coordinates from view
            mouseAimConfig.setCenterXY(crosshair);
            mouseAimConfig.setLeftClickXY(leftClick);
            linesToWrite.add(mouseAimConfig.getData());
        }
        
        // Keyboard keys
        keyList.forEach(movableFloatingActionKey -> {
            if(movableFloatingActionKey != null)
                linesToWrite.add(movableFloatingActionKey.getData());
        });

        // Save Config
        KeymapProfiles profiles = new KeymapProfiles(context);
        profiles.saveProfile(keymapConfig.profile, linesToWrite, context.getPackageName());

        // Reload keymap if service running
        InputService.reloadKeymap();
    }

    public void setupButtons() {
        binding.saveButton.setOnClickListener(v -> hideView());
        binding.addButton.setOnClickListener(v -> addKey());
        binding.mouseLeft.setOnClickListener(v -> addleftClick(DEFAULT_X, DEFAULT_Y));
        binding.crossHair.setOnClickListener(v -> {
            mouseAimConfig = new MouseAimConfig();
            addCrosshair(DEFAULT_X, DEFAULT_Y);
        });

        binding.dPad.setOnClickListener(new View.OnClickListener() {
            int x = 0;
            @Override
            public void onClick(View v) {
                if (x == 0) {
                    addDpad1(DEFAULT_X, DEFAULT_Y);
                    x = 1;
                } else {
                    addDpad2(DEFAULT_X, DEFAULT_Y);
                    x = 0;
                }
            }
        });
    }

    private void addDpad1(float x, float y) {
        if (dpad1 == null) {
            Dpad1Binding binding = Dpad1Binding.inflate(layoutInflater, mainView, true);
            dpad1 = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(dpad1);
                dpad1 = null;
            });
        }
        dpad1.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    private void addDpad2(float x, float y) {
        if (dpad2 == null) {
            Dpad2Binding binding = Dpad2Binding.inflate(layoutInflater, mainView, true);
            dpad2 = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(dpad2);
                dpad2 = null;
            });
        }
        dpad2.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }

    private void addKey(KeymapProfiles.Key key) {
        MovableFloatingActionKey floatingKey = new MovableFloatingActionKey(context);

        floatingKey.setText(key.code.substring(4));
        floatingKey.animate()
                .x(key.x)
                .y(key.y)
                .setDuration(1000)
                .start();
        floatingKey.setOnClickListener(this::onClick);

        mainView.addView(floatingKey);

        keyList.add(floatingKey);
    }

    private void addKey() {
        final KeymapProfiles.Key key = new KeymapProfiles.Key();
        key.code = "KEY_X";
        key.x = DEFAULT_X;
        key.y = DEFAULT_Y;
        addKey(key);
    }

    public void onClick(View view) {
        keyInFocus = ((MovableFloatingActionKey)view);
    }

    private void addCrosshair(float x, float y) {
        if (crosshair == null) {
            CrosshairBinding binding = CrosshairBinding.inflate(layoutInflater, mainView, true);
            crosshair = binding.getRoot();

            binding.closeButton.setOnClickListener(v -> {
                mainView.removeView(crosshair);
                crosshair = null;
            });
            binding.expandButton.setOnClickListener(v -> new ResizableLayout());
            binding.editButton.setOnClickListener(v -> new MouseAimSettings().getDialog(context).show());
        }
        crosshair.animate().x(x).y(y)
                .setDuration(500)
                .start();

        addleftClick(mouseAimConfig.xleftClick,
                     mouseAimConfig.yleftClick);
    }

    private void addleftClick(float x, float y) {
        if (leftClick == null) {
            leftClick = new MovableFloatingActionKey(context);
            leftClick.key.setImageResource(R.drawable.ic_baseline_mouse_36);
            mainView.addView(leftClick);
        }
        leftClick.animate().x(x).y(y)
                .setDuration(500)
                .start();
    }
    class ResizableLayout implements View.OnTouchListener {

        private final View view;

        @SuppressLint("ClickableViewAccessibility")
        public ResizableLayout(){
            ResizableBinding binding1 = ResizableBinding.inflate(layoutInflater, mainView, true);
            view = binding1.getRoot();
            binding1.dragHandle.setOnTouchListener(this);
            binding1.saveButton.setOnClickListener(v -> {
                mainView.removeView(view);
                mouseAimConfig.width = view.getPivotX();
                mouseAimConfig.height = view.getPivotY();
            });
            moveView();
        }
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
                layoutParams.width += x;
                layoutParams.height += y;
                moveView();
            } else {
                v.performClick();
            }
            return true;
        }
        private void moveView(){
            float x = crosshair.getX() - view.getPivotX();
            float y = crosshair.getY() - view.getPivotY();
            view.setX(x);
            view.setY(y);
            view.requestLayout();
        }
    }
}