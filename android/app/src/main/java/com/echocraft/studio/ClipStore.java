package com.echocraft.studio;

import android.content.Context;
import android.os.Environment;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class ClipStore {
    final File root;
    Context context;
    ClipStore(Context c) { this(new File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES),"EchoCraft"));context=c.getApplicationContext(); }
    ClipStore(File directory) {root=directory;root.mkdirs();}
    File newSession() throws IOException {
        File f=new File(root,new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date())+"-"+UUID.randomUUID().toString().substring(0,6));
        if(!f.mkdir()) throw new IOException("无法创建录像目录");
        return f;
    }
    void commit(File folder,JSONObject metadata) throws Exception {
        File tmp=new File(folder,"session.json.tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)) {out.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!tmp.renameTo(new File(folder,"session.json"))) throw new IOException("无法保存录像记录");
    }
    JSONObject metadata(File folder) throws Exception {
        try(FileInputStream in=new FileInputStream(new File(folder,"session.json"));ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            return new JSONObject(bytes.toString("UTF-8")); }
    }
    List<File> sessions() {
        File[] files=root.listFiles(f->f.isDirectory() && new File(f,"session.json").isFile() && !versions(f).isEmpty());
        List<File> result=new ArrayList<>(); if(files!=null) result.addAll(Arrays.asList(files));
        result.sort((a,b)->b.getName().compareTo(a.getName()));return result;
    }
    List<File> versions(File folder) {
        File[] files=folder.listFiles(f->f.getName().endsWith(".mp4") && !f.getName().startsWith("pending-"));
        List<File> result=new ArrayList<>();if(files!=null)result.addAll(Arrays.asList(files));
        result.sort(Comparator.comparing(File::getName));return result;
    }
    void delete(File f) throws IOException {
        String prefix=root.getCanonicalPath()+File.separator;
        if(!f.getCanonicalPath().startsWith(prefix)) throw new IOException("只能删除本 App 的录像");
        boolean session=context!=null&&f.isDirectory();
        if(session) {
            File[] records=f.listFiles(x->x.getName().endsWith(".public.json"));
            if(records!=null)for(File record:records){PublicVideos.remove(context,record);if(!record.delete())throw new IOException("无法删除公共视频索引");}
        }
        File[] children=f.listFiles();if(children!=null)for(File child:children)delete(child);
        if(f.exists()&&!f.delete())throw new IOException("删除失败："+f.getName());
        if(session)PublicVideos.cleanupFolder(f.getName());
    }
    synchronized void reconcile() throws Exception {
        if(context==null)return;
        for(File folder:sessions()) {
            for(File video:versions(folder)) {
                File record=new File(folder,video.getName()+".public.json");
                if(record.isFile()&&!PublicVideos.exists(context,record)) {
                    if(!video.delete())throw new IOException("无法同步删除本地视频");
                    if(!record.delete())throw new IOException("无法删除视频索引");
                }
            }
            if(versions(folder).isEmpty())delete(folder);
        }
        File publicRoot=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),"EchoCraft");
        File[] emptyCandidates=publicRoot.listFiles(File::isDirectory);
        if(emptyCandidates!=null)for(File directory:emptyCandidates)PublicVideos.cleanupFolder(directory.getName());
    }
    synchronized File rename(File folder,String requested) throws Exception {
        String name=requested.trim();
        if(name.isEmpty()||name.length()>80||name.equals(".")||name.equals("..")||name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")||name.endsWith("."))throw new IOException("名称需为 1–80 个字符，不能包含路径或特殊符号");
        if(!folder.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))throw new IOException("无效的作品目录");
        File target=new File(root,name);if(target.equals(folder))return folder;
        if(target.exists())throw new IOException("名称已存在，请换一个名称");
        File[] records=folder.listFiles(f->f.getName().endsWith(".public.json"));
        List<File> moved=new ArrayList<>();
        try {
            if(context!=null&&records!=null)for(File record:records){PublicVideos.move(context,record,name);moved.add(record);}
            if(!folder.renameTo(target))throw new IOException("文件夹重命名失败");
        }catch(Exception e){for(File record:moved)try{PublicVideos.move(context,record,folder.getName());}catch(Exception rollback){e.addSuppressed(rollback);}throw e;}
        if(context!=null)PublicVideos.cleanupFolder(folder.getName());
        return target;
    }
}
