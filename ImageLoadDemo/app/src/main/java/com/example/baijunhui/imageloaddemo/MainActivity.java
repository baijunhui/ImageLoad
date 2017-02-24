package com.example.baijunhui.imageloaddemo;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.bumptech.glide.Glide;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.squareup.picasso.Picasso;


public class MainActivity extends AppCompatActivity {

    ImageView ivImageLoader;
    ImageView ivGlide;
    ImageView ivPicasso;
    LinearLayout activityMain;

    private String imageloaderUrl = "https://timgsa.baidu.com/timg?image&quality=80&size=b9999_10000&sec=1487827322562&di=bbae7e26a371440e76d2fd7618279ae0&imgtype=0&src=http%3A%2F%2Fcdn.duitang.com%2Fuploads%2Fitem%2F201412%2F02%2F20141202212022_uGhLt.jpeg";
    private String ivGlideUrl = "https://timgsa.baidu.com/timg?image&quality=80&size=b9999_10000&sec=1487827322556&di=be8390351e23f3f90e80afeabc9ae6f1&imgtype=0&src=http%3A%2F%2Fimg5q.duitang.com%2Fuploads%2Fitem%2F201409%2F27%2F20140927191445_Uu2dd.jpeg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ivImageLoader = (ImageView) findViewById(R.id.iv_image_loader);
        ivGlide = (ImageView) findViewById(R.id.iv_glide);
        ivPicasso = (ImageView) findViewById(R.id.iv_picasso);

        ImageLoaderConfiguration.Builder builder = new ImageLoaderConfiguration.Builder(this);
        ImageLoader.getInstance().init(builder.build());

        loadImageLoader();

        loadGlider();

        loadPicasso();
    }

    private void loadPicasso() {
        Picasso.with(this).load(ivGlideUrl).into(ivPicasso);
    }

    private void loadGlider() {
        Glide.with(this).load(ivGlideUrl).into(ivGlide);
    }

    private void loadImageLoader() {
        ImageLoader.getInstance().displayImage(imageloaderUrl, ivImageLoader);
    }
}
