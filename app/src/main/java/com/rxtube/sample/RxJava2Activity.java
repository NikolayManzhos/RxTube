package com.rxtube.sample;


import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.SparseArray;

import com.rxtube2.RxTube;
import com.rxtube2.RxTubeFactory;
import com.rxtube2.YtFile;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class RxJava2Activity extends AppCompatActivity {

    private static final String TAG = RxJavaActivity.class.getSimpleName();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String youtubeLink = "http://youtube.com/watch?v=0IKHxjkgop4";

        RxTube rxTube = RxTubeFactory.create(getApplicationContext());
        rxTube.extract(youtubeLink)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(videoResponse -> {
                    SparseArray<YtFile> ytFiles = videoResponse.getYtFiles();
                    int itag = 22;
                    String downloadUrl = ytFiles.get(itag).getUrl();
                    Log.d(TAG, "onExtractionComplete: " + downloadUrl);
                }, err -> Log.e(TAG, "", err));
    }
}
