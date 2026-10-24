package com.echocraft.studio;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    static final int PAPER=0xfff5f4ee,INK=0xff192f2a,TEAL=0xff147c70,MUTED=0xff74817b,ORANGE=0xffdd6b43;
    final Handler ui=new Handler(Looper.getMainLooper());
    LinearLayout root,content,nav;TextView status,timer,recordButton,deviceText,modeText;
    TextureView texture;Switch denoiseSwitch;ProgressBar meter;
    ClipStore store;CameraController camera;CaptureEngine capture;
    boolean library=false,starting=false,resumed=false,destroyed=false,editing=false;
    boolean denoise=true,audioOnly=false;long startedAt=0;
    RadioGroup recordingModes;
    boolean debugCapture=false,debugTriggered=false;
    boolean debugExternal=false;
    int debugSystemVolumeBefore=-1;
    String lastError="";boolean testingUsb=false;
    final String USB_PERMISSION="com.echocraft.studio.USB_PERMISSION";
    final BroadcastReceiver usbReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
        refreshDevice();
        if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())&&capture!=null){capture.warning="外置设备断开，录制已停止";stopRecording();}
    }};
    AudioDeviceCallback audioDevices=new AudioDeviceCallback(){
        public void onAudioDevicesAdded(AudioDeviceInfo[] d){refreshDevice();}
        public void onAudioDevicesRemoved(AudioDeviceInfo[] d){refreshDevice();}
    };
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Legacy registration is used only below API 33.
    public void onCreate(Bundle state) {
        super.onCreate(state);store=new ClipStore(this);
        audioOnly=getSharedPreferences("preferences",0).getBoolean("audioOnly",false);
        boolean audioDebug=(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("debug_audio_capture",false);
        if(audioDebug)audioOnly=true;
        debugCapture=(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("debug_capture",false);
        debugExternal=(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&(getIntent().getBooleanExtra("debug_external_capture",false)||audioDebug);
        if(debugExternal&&!audioDebug)audioOnly=false;
        if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0 && getIntent().hasExtra("enable_bridge"))getSharedPreferences("debug",0).edit().putBoolean("computerBridge",getIntent().getBooleanExtra("enable_bridge",false)).apply();
        getIntent().removeExtra("debug_capture");
        getIntent().removeExtra("debug_audio_capture");getIntent().removeExtra("debug_external_capture");getIntent().removeExtra("enable_bridge");
        IntentFilter filter=new IntentFilter();filter.addAction(USB_PERMISSION);filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(usbReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(usbReceiver,filter);
        ((AudioManager)getSystemService(AUDIO_SERVICE)).registerAudioDeviceCallback(audioDevices,ui);
        build();permissions();
        if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("debug_library_check",false)){
            getIntent().removeExtra("debug_library_check");new Thread(()->{try{
                JSONObject result=PublicVideoChecks.run(this);
                try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),"library-check.json"))){out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            }catch(Exception e){android.util.Log.e("EchoCraft","Library check failed",e);try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),"library-check.json"))){out.write(e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}}).start();
        }
    }
    int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    GradientDrawable bg(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView text(String value,float size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    TextView action(String title,int color,int fill,Runnable task){TextView t=text(title,15,color);t.setGravity(Gravity.CENTER);t.setPadding(dp(16),dp(13),dp(16),dp(13));t.setBackground(bg(fill,14));t.setOnClickListener(v->task.run());return t;}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    void build(){
        root=column();root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(22),dp(18),dp(22),dp(12));
        TextView brand=text("EchoCraft",29,INK);brand.setTypeface(android.graphics.Typeface.create("serif",android.graphics.Typeface.NORMAL));
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));TextView badge=text("设备诊断",12,TEAL);badge.setPadding(dp(10),dp(12),0,dp(12));badge.setOnClickListener(v->diagnostics());header.addView(badge);root.addView(header);
        content=column();root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LinearLayout(this);nav.setPadding(dp(20),dp(10),dp(20),dp(12));root.addView(nav);
        showCapture();
    }
    void navigation(){nav.removeAllViews();nav.addView(action("录制",library?MUTED:TEAL,library?PAPER:0xffe1ece5,()->{if(capture==null&&!editing){library=false;showCapture();}}),new LinearLayout.LayoutParams(0,-2,1));
        TextView list=action("作品库",library?TEAL:MUTED,library?0xffe1ece5:PAPER,()->{if(capture==null&&!editing){library=true;showLibrary();}});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(10);nav.addView(list,lp);}
    void showCapture(){
        closeCamera();content.removeAllViews();navigation();
        recordingModes=new RadioGroup(this);recordingModes.setOrientation(LinearLayout.HORIZONTAL);recordingModes.setPadding(dp(20),0,dp(20),dp(8));
        RadioButton videoMode=new RadioButton(this);videoMode.setId(R.id.record_video_mode);videoMode.setText("视频 · MP4");
        RadioButton audioMode=new RadioButton(this);audioMode.setId(R.id.record_audio_mode);audioMode.setText("纯录音 · MP3");
        recordingModes.addView(videoMode,new RadioGroup.LayoutParams(0,-2,1));recordingModes.addView(audioMode,new RadioGroup.LayoutParams(0,-2,1));recordingModes.check(audioOnly?R.id.record_audio_mode:R.id.record_video_mode);
        recordingModes.setOnCheckedChangeListener((group,id)->{if(capture!=null||starting)return;audioOnly=id==R.id.record_audio_mode;getSharedPreferences("preferences",0).edit().putBoolean("audioOnly",audioOnly).apply();showCapture();permissions();});content.addView(recordingModes);
        LinearLayout device=column();device.setPadding(dp(16),dp(12),dp(16),dp(12));device.setBackground(bg(0xffe8ede5,16));
        deviceText=text("检查音频输入…",15,INK);device.addView(deviceText);TextView hint=text(audioOnly?"外置收音 · 直接保存 MP3":"外置声音 × 手机画面",11,MUTED);hint.setPadding(0,dp(5),0,0);device.addView(hint);
        LinearLayout.LayoutParams card=new LinearLayout.LayoutParams(-1,-2);card.setMargins(dp(20),dp(4),dp(20),dp(12));content.addView(device,card);device.setOnClickListener(v->authorizeUsb());
        FrameLayout frame=new FrameLayout(this);frame.setBackground(bg(0xff14241f,22));frame.setClipToOutline(true);
        LinearLayout.LayoutParams preview=new LinearLayout.LayoutParams(-1,0,1);preview.setMargins(dp(20),0,dp(20),0);content.addView(frame,preview);
        texture=null;
        if(!audioOnly){texture=new TextureView(this);frame.addView(texture,new FrameLayout.LayoutParams(-1,-1));}
        else {TextView audioTitle=text("MP3\n\n只记录声音",30,0xfff3f1e7);audioTitle.setGravity(Gravity.CENTER);frame.addView(audioTitle,new FrameLayout.LayoutParams(-1,-1));}
        TextView tag=text(audioOnly?"AUDIO RECORDER":"LIVE VIEW",10,0xfff3f1e7);tag.setLetterSpacing(.16f);tag.setPadding(dp(16),dp(14),0,0);frame.addView(tag);
        timer=text("00:00",16,0xfff3f1e7);timer.setTypeface(android.graphics.Typeface.MONOSPACE);FrameLayout.LayoutParams timeLp=new FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT|Gravity.TOP);timeLp.setMargins(0,dp(12),dp(16),0);frame.addView(timer,timeLp);
        status=text("连接收音器，开始记录现场",12,0xfff3f1e7);status.setPadding(dp(16),dp(12),dp(16),dp(14));FrameLayout.LayoutParams statusLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);frame.addView(status,statusLp);
        if(texture!=null)texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture s,int w,int h){openCamera();}
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s,int w,int h){}public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture s){closeCamera();return true;}public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture s){} });
        LinearLayout controls=column();controls.setPadding(dp(22),dp(12),dp(22),dp(8));content.addView(controls);
        LinearLayout toggle=new LinearLayout(this);toggle.setGravity(Gravity.CENTER_VERTICAL);LinearLayout words=column();
        modeText=text("实时降噪",15,INK);words.addView(modeText);words.addView(text("边录边处理，保留声音细节",11,MUTED));toggle.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        denoiseSwitch=new Switch(this);denoiseSwitch.setChecked(denoise);denoiseSwitch.setOnCheckedChangeListener((b,checked)->{denoise=checked;if(capture!=null)capture.setDenoise(checked);});toggle.addView(denoiseSwitch);controls.addView(toggle);
        meter=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);meter.setMax(1000);meter.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(-1,dp(5));mlp.setMargins(0,dp(12),0,dp(12));controls.addView(meter,mlp);
        recordButton=action(audioOnly?"●  开始录音":"●  开始录像",0xffffffff,TEAL,()->{if(capture==null&&!starting)startRecording(false);else if(capture!=null)stopRecording();});controls.addView(recordButton);
        refreshDevice();if(texture!=null&&texture.isAvailable())openCamera();
        if(audioOnly&&debugExternal&&!debugTriggered){debugTriggered=true;ui.postDelayed(()->startRecording(false),800);}
    }
    void refreshDevice(){if(destroyed||deviceText==null||library)return;runOnUiThread(()->{
        try{UsbDevice echo=AudioInputs.echoDevice(this);AudioDeviceInfo audio=AudioInputs.usbAudio(this);
            if(bridgeEnabled())deviceText.setText("○  电脑中转调试 · 待音频验证");
            else if(echo!=null){UsbManager m=(UsbManager)getSystemService(USB_SERVICE);deviceText.setText(m.hasPermission(echo)?"○  检测到 EchoCraft · 待音频验证":"○  EchoCraft 已连接 · 点击授权");}
            else if(audio!=null)deviceText.setText("●  "+audio.getProductName());
            else deviceText.setText("○  等待连接外置收音器");
        }catch(Exception e){deviceText.setText("设备读取失败 · 点击重试");}
    });}
    void authorizeUsb(){UsbDevice device=AudioInputs.echoDevice(this);if(device==null){toast("将手机接到开发板的 USB 接口；COM 接口供电脑调试使用");return;}
        UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);if(!manager.hasPermission(device))manager.requestPermission(device,PendingIntent.getBroadcast(this,0,new Intent(USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE));else toast("设备已授权，可以录像");}
    boolean hasPermissions(){return (audioOnly||checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    void permissions(){if(!hasPermissions())requestPermissions(audioOnly?new String[]{Manifest.permission.RECORD_AUDIO}:new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},10);}
    public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(hasPermissions())openCamera();else toast("录像需要相机和录音权限，可在系统设置中开启");}
    void openCamera(){if(audioOnly||!resumed||library||camera!=null||texture==null||!texture.isAvailable()||!hasPermissions())return;
        camera=new CameraController(this,texture,new CameraController.Events(){public void ready(){runOnUiThread(()->{if(status!=null)status.setText(lastError.isEmpty()?"预览就绪 · 音频来源以录像时校验为准":"上次录制未开始 · 点击右上角设备诊断");
            if((debugCapture||debugExternal)&&!debugTriggered){debugTriggered=true;ui.postDelayed(()->startRecording(debugCapture),800);}});}public void error(String message){runOnUiThread(()->{android.util.Log.e("EchoCraft","Camera: "+message);status.setText(message);toast(message);if(capture!=null){capture.warning=message;stopRecording();}else closeCamera();});}});camera.open();}
    void closeCamera(){if(camera!=null){camera.close();camera=null;}}
    void startRecording(boolean internalTest){
        if(starting||capture!=null||testingUsb)return;if(!hasPermissions()){permissions();return;}
        if(!audioOnly&&(camera==null||camera.device==null)){openCamera();toast("相机尚未就绪，请检查权限并稍候重试");return;}
        if(!internalTest&&!bridgeEnabled()&&AudioInputs.echoDevice(this)==null&&AudioInputs.usbAudio(this)==null){toast("未连接外置收音器，不会使用手机麦克风代录");return;}
        if(!internalTest&&!bridgeEnabled()){UsbDevice d=AudioInputs.echoDevice(this);if(d!=null&&!((UsbManager)getSystemService(USB_SERVICE)).hasPermission(d)){authorizeUsb();return;}}
        try {
            File folder=(internalTest||debugExternal)?new File(getCacheDir(),"debug-"+System.currentTimeMillis()):store.newSession();folder.mkdirs();starting=true;
            if(internalTest||debugExternal)debugSystemVolumeBefore=((AudioManager)getSystemService(AUDIO_SERVICE)).getStreamVolume(AudioManager.STREAM_MUSIC);
            setModesEnabled(false);recordButton.setText("正在连接音频…");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            capture=new CaptureEngine(this,camera,folder,denoise,audioOnly,new CaptureEngine.Listener(){
                public void started(String input){runOnUiThread(()->{starting=false;lastError="";startedAt=SystemClock.elapsedRealtime();status.setText(input);deviceText.setText("●  音频已验证 · "+input);recordButton.setText(audioOnly?"■  结束录音":"■  结束录像");recordButton.setBackground(bg(ORANGE,14));tick();
                    if(internalTest||debugExternal){
                        if(debugExternal){ui.postDelayed(()->{if(capture!=null)denoiseSwitch.setChecked(false);},1500);ui.postDelayed(()->{if(capture!=null)denoiseSwitch.setChecked(true);},3000);}
                        ui.postDelayed(()->stopRecording(),5000);
                    }});}
                public void level(float peak){ui.post(()->{if(meter!=null)meter.setProgress((int)(Math.sqrt(peak)*1000));});}
                public void finished(File f,JSONObject metadata,String warning){
                    try{metadata.put("debugInternal",internalTest);store.commit(f,metadata);}catch(Exception e){failed(e.getMessage());return;}
                    if(!internalTest&&!debugExternal)try{PublicVideos.publish(MainActivity.this,new File(f,audioOnly?"original.mp3":"original.mp4"));}catch(Exception e){saveError("公共视频保存失败："+e.getMessage());runOnUiThread(()->toast("作品已保存，公共视频同步失败，可重启重试"));}
                    runOnUiThread(()->{resetRecording();toast(warning.isEmpty()?"已保存到作品库":warning);if(library)showLibrary();});
                    if(internalTest||debugExternal){if(audioOnly)runDebugAudioEdit(f);else runDebugEdit(f);}
                }
                public void failed(String message){saveError(message);runOnUiThread(()->{resetRecording();status.setText("录制失败 · 点击右上角设备诊断查看原因");toast("录制未保存，请查看设备诊断");});android.util.Log.e("EchoCraft","Capture failed: "+message);
                    if(internalTest||debugExternal)writeDebugResult("error",message);}
            });capture.start(internalTest);
        }catch(Exception e){resetRecording();toast(e.getMessage());}
    }
    void setModesEnabled(boolean enabled){if(recordingModes!=null)for(int i=0;i<recordingModes.getChildCount();i++)recordingModes.getChildAt(i).setEnabled(enabled);}
    void tick(){if(capture==null||starting)return;long seconds=(SystemClock.elapsedRealtime()-startedAt)/1000;timer.setText(String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60));ui.postDelayed(this::tick,500);}
    void stopRecording(){if(capture!=null){recordButton.setText("正在保存…");recordButton.setEnabled(false);capture.stop();}}
    void resetRecording(){capture=null;starting=false;setModesEnabled(true);if(recordButton!=null){recordButton.setEnabled(true);recordButton.setText(audioOnly?"●  开始录音":"●  开始录像");recordButton.setBackground(bg(TEAL,14));}
        if(timer!=null)timer.setText("00:00");if(meter!=null)meter.setProgress(0);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    void showLibrary(){try{store.reconcile();}catch(Exception e){toast("同步作品库失败："+e.getMessage());}closeCamera();content.removeAllViews();navigation();
        LinearLayout heading=column();heading.setPadding(dp(22),dp(8),dp(22),dp(18));heading.addView(text("你的声音作品",25,INK));
        heading.addView(text("只收录 EchoCraft 录制的视频和 MP3",12,MUTED));content.addView(heading);
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();list.setPadding(dp(20),0,dp(20),dp(12));scroll.addView(list);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        List<File> sessions=store.sessions();if(sessions.isEmpty()){TextView empty=text("还没有作品\n\n连接收音器，录下第一段现场。",16,MUTED);empty.setPadding(dp(12),dp(65),dp(12),0);list.addView(empty);}
        for(File folder:sessions) {
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setBackground(bg(0xffffffff,18));
            LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1,-2);rlp.bottomMargin=dp(12);list.addView(row,rlp);
            ImageView thumbnail=new ImageView(this);thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);thumbnail.setBackground(bg(0xffdce7df,12));thumbnail.setClipToOutline(true);row.addView(thumbnail,new LinearLayout.LayoutParams(dp(78),dp(96)));
            LinearLayout words=column();words.setPadding(dp(14),0,0,0);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            String title=folder.getName();words.addView(text(title,16,INK));
            try{JSONObject m=store.metadata(folder);words.addView(text((m.optString("format","MP4")+" · ")+String.format(Locale.US,"%02d:%02d  ·  %d 个版本",m.optLong("durationMs")/60000,(m.optLong("durationMs")/1000)%60,store.versions(folder).size()),12,MUTED));
                TextView source=text(m.optString("input","外置收音"),11,TEAL);source.setPadding(0,dp(8),0,0);words.addView(source);}catch(Exception ignored){}
            TextView rename=action("重命名",TEAL,PAPER,()->renameClip(folder));words.addView(rename);
            if(!store.versions(folder).isEmpty()&&store.versions(folder).get(0).getName().endsWith(".mp3")){thumbnail.setImageResource(android.R.drawable.ic_btn_speak_now);thumbnail.setContentDescription("MP3 音频作品");}
            row.setOnClickListener(v->play(folder));
            new Thread(()->{try{MediaMetadataRetriever r=new MediaMetadataRetriever();r.setDataSource(store.versions(folder).get(0).getAbsolutePath());Bitmap b=Build.VERSION.SDK_INT>=27?r.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,160,220):r.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);r.release();ui.post(()->{if(!destroyed)thumbnail.setImageBitmap(b);});}catch(Exception ignored){}}).start();
        }
    }
    void play(File folder){
        if(!store.versions(folder).isEmpty()&&store.versions(folder).get(0).getName().endsWith(".mp3")){playAudio(folder);return;}
        List<File> versions=store.versions(folder);if(versions.isEmpty())return;
        LinearLayout panel=column();panel.setPadding(dp(16),dp(8),dp(16),dp(12));Spinner spinner=new Spinner(this);List<String> names=new ArrayList<>();
        for(File v:versions)names.add(v.getName().startsWith("original.")?"实录原版本":"编辑版 · "+v.getName().substring(5,18));
        spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));panel.addView(spinner);
        VideoView player=new VideoView(this);panel.addView(player,new LinearLayout.LayoutParams(-1,dp(350)));MediaController controller=new MediaController(this);controller.setAnchorView(player);player.setMediaController(controller);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){player.setVideoPath(versions.get(position).getAbsolutePath());player.start();}public void onNothingSelected(AdapterView<?> p){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("作品预览").setView(panel).setPositiveButton("编辑声音",(d,w)->{player.stopPlayback();edit(versions.get(spinner.getSelectedItemPosition()));})
            .setNeutralButton("删除本次",(d,w)->{player.stopPlayback();confirmDelete(folder);}).setNegativeButton("关闭",null).create();dialog.setOnDismissListener(d->player.stopPlayback());dialog.show();
    }
    void renameClip(File folder){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(folder.getName());input.selectAll();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("重命名作品及文件夹").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{store.reconcile();store.rename(folder,input.getText().toString());dialog.dismiss();showLibrary();toast("名称与文件夹已更新");}catch(Exception e){input.setError(e.getMessage());}
        }));dialog.show();
    }
    void playAudio(File folder){
        List<File> versions=store.versions(folder);if(versions.isEmpty())return;
        LinearLayout panel=column();panel.setPadding(dp(20),dp(12),dp(20),dp(16));
        panel.addView(text("MP3 · 音频作品",23,TEAL));Spinner select=new Spinner(this);List<String> labels=new ArrayList<>();
        for(File file:versions)labels.add(file.getName().startsWith("original.")?"录音原版本":"编辑版 · "+file.getName().substring(5,18));
        select.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));panel.addView(select);
        TextView time=text("00:00 / 00:00",16,MUTED);panel.addView(time);SeekBar seek=new SeekBar(this);panel.addView(seek);
        MediaPlayer[] player={null};boolean[] ready={false},closed={false};
        TextView toggle=action("暂停 / 播放",0xffffffff,TEAL,()->{if(ready[0]&&player[0]!=null){if(player[0].isPlaying())player[0].pause();else player[0].start();}});panel.addView(toggle);
        Runnable[] tick={null};tick[0]=()->{if(closed[0])return;if(ready[0]&&player[0]!=null){int pos=player[0].getCurrentPosition(),duration=player[0].getDuration();seek.setMax(duration);seek.setProgress(pos);time.setText(String.format(Locale.US,"%02d:%02d / %02d:%02d",pos/60000,pos/1000%60,duration/60000,duration/1000%60));}ui.postDelayed(tick[0],400);};
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&ready[0])player[0].seekTo(p);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        select.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){
            ready[0]=false;if(player[0]!=null)player[0].release();MediaPlayer current=new MediaPlayer();player[0]=current;
            try{current.setDataSource(versions.get(position).getAbsolutePath());current.setOnPreparedListener(mp->{if(!closed[0]){ready[0]=true;mp.start();}});current.setOnErrorListener((mp,what,extra)->{ready[0]=false;toast("音频播放失败");return true;});current.prepareAsync();}catch(Exception e){toast(e.getMessage());}
        }public void onNothingSelected(AdapterView<?> p){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(folder.getName()).setView(panel).setPositiveButton("编辑声音",(d,w)->edit(versions.get(select.getSelectedItemPosition())))
            .setNeutralButton("删除本次",(d,w)->confirmDelete(folder)).setNegativeButton("关闭",null).create();
        dialog.setOnDismissListener(d->{closed[0]=true;ui.removeCallbacks(tick[0]);if(player[0]!=null)player[0].release();});dialog.show();ui.post(tick[0]);
    }
    void runDebugAudioEdit(File folder){new Thread(()->{try{
        File source=new File(folder,"original.mp3"),edited=VideoEditor.edit(source,false,50,m->{}),denoised=VideoEditor.edit(source,true,100,m->{});
        JSONObject report=new JSONObject();report.put("checks",PublicVideoChecks.runAudio(this,source));report.put("repeatCaptureAfterDelete",DebugCaptureCycle.run(this));report.put("cameraOpened",camera!=null);report.put("capture",source.getAbsolutePath());report.put("edit",edited.getAbsolutePath());report.put("denoiseEdit",denoised.getAbsolutePath());
        report.put("systemVolumeBefore",debugSystemVolumeBefore);report.put("systemVolume",((AudioManager)getSystemService(AUDIO_SERVICE)).getStreamVolume(AudioManager.STREAM_MUSIC));
        try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),"debug-audio-result.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }catch(Exception e){writeDebugResult("audioError",e.toString());android.util.Log.e("EchoCraft","MP3 check failed",e);}},"EchoCraft-mp3-check").start();}
    void confirmDelete(File folder){new AlertDialog.Builder(this).setTitle("删除这次录制？").setMessage("将删除原始录制、全部编辑版本和记录，手机上的对应文件也会一起删除。此操作无法撤销。")
        .setNegativeButton("保留",null).setPositiveButton("删除",(d,w)->{try{store.delete(folder);showLibrary();}catch(Exception e){toast(e.getMessage());}}).show();}
    void edit(File source){
        LinearLayout panel=column();panel.setPadding(dp(22),dp(12),dp(22),dp(12));Switch reduce=new Switch(this);reduce.setText("再次智能降噪");reduce.setChecked(true);panel.addView(reduce);
        TextView volume=text("视频音量 100%",18,INK);volume.setPadding(0,dp(22),0,dp(8));panel.addView(volume);
        SeekBar slider=new SeekBar(this);slider.setMax(300);slider.setProgress(100);panel.addView(slider);
        panel.addView(text("调整写入视频的音轨，不改变手机系统音量。\n原版本保留，处理后生成一个新版本。",12,MUTED));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){volume.setText("视频音量 "+p+"%");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        new AlertDialog.Builder(this).setTitle("编辑声音").setView(panel).setNegativeButton("取消",null).setPositiveButton("生成新版本",(d,w)->{
            editing=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ProgressDialog progress=new ProgressDialog(this);progress.setMessage("准备处理…");progress.setIndeterminate(true);progress.setCancelable(false);progress.show();
            new Thread(()->{try{File result=VideoEditor.edit(source,reduce.isChecked(),slider.getProgress(),m->ui.post(()->progress.setMessage(m)));
                PublicVideos.publish(MainActivity.this,result);
                ui.post(()->{progress.dismiss();editing=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);toast("新版本已保存");showLibrary();play(result.getParentFile());});
            }catch(Exception e){ui.post(()->{progress.dismiss();editing=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);toast("处理失败："+e.getMessage());});}},"EchoCraft-editor").start();
        }).show();
    }
    void runDebugEdit(File folder){new Thread(()->{try{File edited=VideoEditor.edit(new File(folder,"original.mp4"),false,50,m->android.util.Log.i("EchoCraft",m));
        JSONObject result=new JSONObject();result.put("capture",new File(folder,"original.mp4").getAbsolutePath());result.put("edit",edited.getAbsolutePath());result.put("folder",folder.getAbsolutePath());
        File denoised=VideoEditor.edit(new File(folder,"original.mp4"),true,100,m->android.util.Log.i("EchoCraft",m));result.put("denoiseEdit",denoised.getAbsolutePath());
        result.put("checks",DebugChecks.run(this,folder,new File(folder,"original.mp4")));
        result.put("systemVolumeBefore",debugSystemVolumeBefore);
        result.put("systemVolume",((AudioManager)getSystemService(AUDIO_SERVICE)).getStreamVolume(AudioManager.STREAM_MUSIC));
        try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),"debug-result.json"))){out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        android.util.Log.i("EchoCraft","DEBUG_SUCCESS "+result);
    }catch(Exception e){writeDebugResult("error",e.toString());android.util.Log.e("EchoCraft","Debug edit failed",e);}}).start();}
    void writeDebugResult(String key,String message){try{JSONObject result=new JSONObject();result.put(key,message);try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),"debug-result.json"))){out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}}
    void saveError(String message){lastError=message;try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"last-diagnostics.txt"))){out.write(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}
    void diagnostics(){
        StringBuilder report=new StringBuilder("EchoCraft 0.1.0\n");UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);
        report.append("电脑中转：").append(bridgeEnabled()).append('\n');
        for(UsbDevice d:manager.getDeviceList().values())report.append("\nUSB：").append(d.getProductName()).append("\nVID/PID：").append(Integer.toHexString(d.getVendorId())).append('/').append(Integer.toHexString(d.getProductId())).append("\n访问授权：").append(manager.hasPermission(d)).append("\n接口数：").append(d.getInterfaceCount()).append('\n');
        report.append("\n最近结果：\n").append(lastError.isEmpty()?"暂无失败记录":lastError);
        TextView label=text(report.toString(),13,INK);label.setTextIsSelectable(true);label.setPadding(dp(20),dp(12),dp(20),dp(12));ScrollView scroll=new ScrollView(this);LinearLayout panel=column();panel.addView(label);scroll.addView(panel);
        if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0){Switch bridge=new Switch(this);bridge.setText("电脑中转调试");bridge.setChecked(bridgeEnabled());bridge.setEnabled(capture==null&&!testingUsb);bridge.setPadding(dp(20),0,dp(20),dp(12));panel.addView(bridge);
            bridge.setOnCheckedChangeListener((b,enabled)->{getSharedPreferences("debug",0).edit().putBoolean("computerBridge",enabled).apply();refreshDevice();});}
        new AlertDialog.Builder(this).setTitle("设备诊断").setView(scroll).setNegativeButton("关闭",null)
            .setNeutralButton("复制信息",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("EchoCraft",report));toast("诊断信息已复制");})
            .setPositiveButton("测试收音（2秒）",(d,w)->testUsb()).show();
    }
    boolean bridgeEnabled(){return (getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getSharedPreferences("debug",0).getBoolean("computerBridge",false);}
    void testUsb(){
        if(capture!=null||starting||testingUsb){toast("请先结束当前录像");return;}
        UsbDevice device=AudioInputs.echoDevice(this);if(device!=null&&!((UsbManager)getSystemService(USB_SERVICE)).hasPermission(device)){authorizeUsb();return;}
        testingUsb=true;ProgressDialog progress=new ProgressDialog(this);progress.setMessage("正在验证外置音频传输…");progress.setCancelable(false);progress.show();
        new Thread(()->{AudioInputs.Input input=null;String result;
            try{input=AudioInputs.create(this,false);input.start(denoise);short[] pcm=new short[256];long count=0;int peak=0;while(count<64000){int n=input.read(pcm);if(n<=0)throw new IOException("音频提前结束");count+=n;for(int i=0;i<n;i++)peak=Math.max(peak,Math.abs((int)pcm[i]));}
                result="音频传输通过\n样本："+count+"\n峰值："+peak+" / 32768\n"+input.diagnostics();
            }catch(Exception e){result="音频测试失败："+e.getMessage()+(input==null?"":"\n"+input.diagnostics());}
            finally{if(input!=null)input.close();}
            saveError(result);ui.post(()->{testingUsb=false;progress.dismiss();diagnostics();});
        },"EchoCraft-usb-test").start();
    }
    void toast(String message){if(!destroyed)Toast.makeText(this,message==null?"操作失败":message,Toast.LENGTH_LONG).show();}
    protected void onResume(){super.onResume();resumed=true;try{store.reconcile();}catch(Exception e){toast("同步作品库失败："+e.getMessage());}if(library)showLibrary();refreshDevice();openCamera();}
    protected void onPause(){resumed=false;if(capture!=null)stopRecording();closeCamera();super.onPause();}
    protected void onDestroy(){destroyed=true;ui.removeCallbacksAndMessages(null);try{unregisterReceiver(usbReceiver);}catch(Exception ignored){}
        ((AudioManager)getSystemService(AUDIO_SERVICE)).unregisterAudioDeviceCallback(audioDevices);closeCamera();super.onDestroy();}
}
