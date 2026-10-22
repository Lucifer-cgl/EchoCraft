package com.echocraft.studio;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import org.json.JSONObject;

/** Explicit debug-only check: creates and deletes its own isolated clips. */
final class PublicVideoChecks {
    static JSONObject run(Context context) throws Exception {
        ClipStore store=new ClipStore(context);File folder=store.newSession();
        JSONObject result=new JSONObject();
        try {
            File video=new File(folder,"original.mp4");
            try(InputStream in=context.getAssets().open("library-check.mp4")){Files.copy(in,video.toPath());}
            store.commit(folder,new JSONObject().put("durationMs",1000));
            PublicVideos.publish(context,video);
            File record=new File(folder,"original.mp4.public.json");
            if(!PublicVideos.exists(context,record))throw new IOException("publish failed");
            String oldName=folder.getName();
            folder=store.rename(folder,"Library-check-"+System.currentTimeMillis());
            record=new File(folder,"original.mp4.public.json");
            try(android.database.Cursor cursor=context.getContentResolver().query(PublicVideos.read(record),new String[]{android.provider.MediaStore.Video.Media.RELATIVE_PATH},null,null,null)){
                if(cursor==null||!cursor.moveToFirst()||!cursor.getString(0).contains(folder.getName()))throw new IOException("rename path failed");
            }
            result.put("renamePublicAndPrivate",true);
            context.getContentResolver().delete(PublicVideos.read(record),null,null);
            PublicVideos.publish(context,new File(folder,"original.mp4"));
            if(PublicVideos.exists(context,record))throw new IOException("deleted clip resurrected");
            store.reconcile();
            if(folder.exists())throw new IOException("deleted clip remains in library");
            result.put("galleryDeletionRemovesLibraryWithoutResurrection",true);
            File publicFolder=new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),"EchoCraft/"+folder.getName());
            result.put("emptyPublicFolderRemoved",!publicFolder.exists());
            result.put("oldRenameFolderRemoved",!new File(publicFolder.getParentFile(),oldName).exists());
            folder=store.newSession();
            try(InputStream in=context.getAssets().open("library-check.mp4")){Files.copy(in,new File(folder,"original.mp4").toPath());}
            store.commit(folder,new JSONObject());
            PublicVideos.publish(context,new File(folder,"original.mp4"));
            record=new File(folder,"original.mp4.public.json");
            android.net.Uri uri=PublicVideos.read(record);
            store.delete(folder);
            try(android.database.Cursor cursor=context.getContentResolver().query(uri,new String[]{android.provider.MediaStore.Video.Media._ID},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst())throw new IOException("App deletion left gallery video");
            }
            result.put("appDeletionRemovesGalleryAndPrivateFolder",!folder.exists());
            return result;
        }finally{if(folder.exists())store.delete(folder);}
    }
}
