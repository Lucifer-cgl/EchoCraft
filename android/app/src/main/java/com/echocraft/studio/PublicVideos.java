package com.echocraft.studio;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.*;
import org.json.JSONObject;

/** Public copies are tracked per private clip; unrelated gallery videos are untouched. */
final class PublicVideos {
    static synchronized void publish(Context context, File video) throws Exception {
        if(Build.VERSION.SDK_INT<29)return;
        ContentResolver resolver=context.getContentResolver();
        File record=new File(video.getParentFile(),video.getName()+".public.json");
        if(record.isFile()) {
            // A missing published item represents a user deletion, never an export retry.
            return;
        }
        ContentValues values=new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,"EchoCraft-"+video.getParentFile().getName()+"-"+video.getName());
        boolean audio=video.getName().endsWith(".mp3");
        values.put(MediaStore.Video.Media.MIME_TYPE,audio?"audio/mpeg":"video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,(audio?"Music":"Movies")+"/EchoCraft/"+video.getParentFile().getName());
        values.put(MediaStore.Video.Media.IS_PENDING,1);
        Uri uri=resolver.insert(audio?MediaStore.Audio.Media.EXTERNAL_CONTENT_URI:MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IOException("无法创建公共视频");
        try {
            try(InputStream in=new FileInputStream(video);OutputStream out=resolver.openOutputStream(uri)) {
                if(out==null)throw new IOException("无法写入公共视频");
                byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            File temp=new File(record.getPath()+".tmp");
            try(FileOutputStream out=new FileOutputStream(temp)) {
                out.write(new JSONObject().put("uri",uri.toString()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();
            }
            if(!temp.renameTo(record))throw new IOException("无法保存公共视频索引");
            values.clear();values.put(MediaStore.Video.Media.IS_PENDING,0);resolver.update(uri,values,null,null);
        } catch(Exception e) {resolver.delete(uri,null,null);record.delete();throw e;}
    }
    static Uri read(File record) throws Exception {
        return Uri.parse(new JSONObject(new String(java.nio.file.Files.readAllBytes(record.toPath()),java.nio.charset.StandardCharsets.UTF_8)).getString("uri"));
    }
    static synchronized void remove(Context context,File record) throws IOException {
        try {context.getContentResolver().delete(read(record),null,null);}
        catch(Exception e){throw new IOException("无法删除公共视频，请重试",e);}
    }
    static boolean exists(Context context, File record) throws Exception {
        try(android.database.Cursor cursor=context.getContentResolver().query(read(record),new String[]{MediaStore.Video.Media._ID},null,null,null)) {
            return cursor!=null&&cursor.moveToFirst();
        }
    }
    static void cleanupFolder(String name) {
        // Only remove this exact empty directory; never recursively remove public storage.
        for(String kind:new String[]{android.os.Environment.DIRECTORY_MOVIES,android.os.Environment.DIRECTORY_MUSIC}){
            File folder=new File(android.os.Environment.getExternalStoragePublicDirectory(kind),"EchoCraft/"+name);
            String[] children=folder.list();if(children!=null&&children.length==0)folder.delete();
        }
    }
    static void move(Context context,File record,String folder) throws Exception {
        if(Build.VERSION.SDK_INT<29)return;
        String kind=record.getName().endsWith(".mp3.public.json")?"Music":"Movies";
        ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.RELATIVE_PATH,kind+"/EchoCraft/"+folder);
        if(context.getContentResolver().update(read(record),values,null,null)!=1)throw new IOException("公共视频已删除，请刷新作品库");
    }
}
