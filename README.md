# ImageLoad

图片加载，分析图片怎么加载到Imageview上的，主要分析了Imageloader，Glide和Picasso。

1、ImageLoader先分析下它
 1）用法
 public void displayImage(String uri, ImageView imageView, DisplayImageOptions options)
 这是其基本用法，但是使用之前要使用
 ImageLoaderConfiguration.Builder builder = new ImageLoaderConfiguration.Builder(this);
 ImageLoader.getInstance().init(builder.build());
 进行初始化，builder里面可以设置很多东西，比如内存啦，线程数啦，缓存地址啦等，可以自己设置试试

 2）displayImage方法最终都会调用到重载的这个方法上
 public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options,
			ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
 
 我们来着重研究下这个里面的实现

 public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options,
			ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		//检查是否进行了初始化
		checkConfiguration();
		//如果传入ImageView为null，则抛出异常
		if (imageAware == null) {
			throw new IllegalArgumentException(ERROR_WRONG_ARGUMENTS);
		}
		//事件监听为null的话，就给他传入个空的监听事件
		if (listener == null) {
			listener = emptyListener;
		}
		//配置为空的话，会有个默认配置，是之前初始化的时候设置的
		if (options == null) {
			options = configuration.defaultDisplayImageOptions;
		}

		//图片url是空的话，就拿配置的空的资源传入，进行显示，直接return了就，不进行下面的操作了
		if (TextUtils.isEmpty(uri)) {
			engine.cancelDisplayTaskFor(imageAware);
			listener.onLoadingStarted(uri, imageAware.getWrappedView());
			if (options.shouldShowImageForEmptyUri()) {
				imageAware.setImageDrawable(options.getImageForEmptyUri(configuration.resources));
			} else {
				imageAware.setImageDrawable(null);
			}
			listener.onLoadingComplete(uri, imageAware.getWrappedView(), null);
			return;
		}

		//根据图片size和url生成一个value，把ImageAware当成key，存入到一个map缓冲中，每次都更新成最新的
		ImageSize targetSize = ImageSizeUtils.defineTargetSizeForView(imageAware, configuration.getMaxImageSize());
		String memoryCacheKey = MemoryCacheUtils.generateKey(uri, targetSize);
		engine.prepareDisplayTaskFor(imageAware, memoryCacheKey);

		//开始调用回调的onStarted方法
		listener.onLoadingStarted(uri, imageAware.getWrappedView());

		//先从缓存中拿缓冲的bitmap，有的话，就不从网络获取了
		Bitmap bmp = configuration.memoryCache.get(memoryCacheKey);
		if (bmp != null && !bmp.isRecycled()) {
			L.d(LOG_LOAD_IMAGE_FROM_MEMORY_CACHE, memoryCacheKey);

			if (options.shouldPostProcess()) {
				ImageLoadingInfo imageLoadingInfo = new ImageLoadingInfo(uri, imageAware, targetSize, memoryCacheKey,
						options, listener, progressListener, engine.getLockForUri(uri));
				ProcessAndDisplayImageTask displayTask = new ProcessAndDisplayImageTask(engine, bmp, imageLoadingInfo,
						defineHandler(options));
				//同步的话，直接调用run()方法，异步的话，调用线程池的execute()方法
				if (options.isSyncLoading()) {
					displayTask.run();
				} else {
					engine.submit(displayTask);
				}
			} else {
				options.getDisplayer().display(bmp, imageAware, LoadedFrom.MEMORY_CACHE);
				listener.onLoadingComplete(uri, imageAware.getWrappedView(), bmp);
			}
		} else {
			//是否显示loading的默认图
			if (options.shouldShowImageOnLoading()) {
				imageAware.setImageDrawable(options.getImageOnLoading(configuration.resources));
			} else if (options.isResetViewBeforeLoading()) {
				imageAware.setImageDrawable(null);
			}

			ImageLoadingInfo imageLoadingInfo = new ImageLoadingInfo(uri, imageAware, targetSize, memoryCacheKey,
					options, listener, progressListener, engine.getLockForUri(uri));
			LoadAndDisplayImageTask displayTask = new LoadAndDisplayImageTask(engine, imageLoadingInfo,
					defineHandler(options));
			if (options.isSyncLoading()) {
				displayTask.run();
			} else {
				engine.submit(displayTask);
			}
		}
	}

	3）最终异步调用的话，都是调用到了engine.submit(displayTask);这个方法，我们来看下这个类ImageLoaderEngine的submit（）方法

	/** Submits task to execution pool */
	void submit(final LoadAndDisplayImageTask task) {
		taskDistributor.execute(new Runnable() {
			@Override
			public void run() {
				File image = configuration.diskCache.get(task.getLoadingUri());
				boolean isImageCachedOnDisk = image != null && image.exists();
				initExecutorsIfNeed();
				if (isImageCachedOnDisk) {
					taskExecutorForCachedImages.execute(task);
				} else {
					taskExecutor.execute(task);
				}
			}
		});
	}

	4）可以看到最终又是调用了线程池方法execute执行这个task的run方法,  LoadAndDisplayImageTask这个类实现了Runnable

	@Override
	public void run() {
		if (waitIfPaused()) return;
		if (delayIfNeed()) return;

		ReentrantLock loadFromUriLock = imageLoadingInfo.loadFromUriLock;
		L.d(LOG_START_DISPLAY_IMAGE_TASK, memoryCacheKey);
		if (loadFromUriLock.isLocked()) {
			L.d(LOG_WAITING_FOR_IMAGE_LOADED, memoryCacheKey);
		}

		loadFromUriLock.lock();
		Bitmap bmp;
		try {
			checkTaskNotActual();

			//从缓存中获取bitmap
			bmp = configuration.memoryCache.get(memoryCacheKey);
			if (bmp == null || bmp.isRecycled()) {
				//真正获取bitmap的地方
				bmp = tryLoadBitmap();
				if (bmp == null) return; // listener callback already was fired

				checkTaskNotActual();
				checkTaskInterrupted();

				if (options.shouldPreProcess()) {
					L.d(LOG_PREPROCESS_IMAGE, memoryCacheKey);
					bmp = options.getPreProcessor().process(bmp);
					if (bmp == null) {
						L.e(ERROR_PRE_PROCESSOR_NULL, memoryCacheKey);
					}
				}

				//如果缓存的话，就缓存起来
				if (bmp != null && options.isCacheInMemory()) {
					L.d(LOG_CACHE_IMAGE_IN_MEMORY, memoryCacheKey);
					configuration.memoryCache.put(memoryCacheKey, bmp);
				}
			} else {
				loadedFrom = LoadedFrom.MEMORY_CACHE;
				L.d(LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING, memoryCacheKey);
			}

			if (bmp != null && options.shouldPostProcess()) {
				L.d(LOG_POSTPROCESS_IMAGE, memoryCacheKey);
				bmp = options.getPostProcessor().process(bmp);
				if (bmp == null) {
					L.e(ERROR_POST_PROCESSOR_NULL, memoryCacheKey);
				}
			}
			checkTaskNotActual();
			checkTaskInterrupted();
		} catch (TaskCancelledException e) {
			fireCancelEvent();
			return;
		} finally {
			loadFromUriLock.unlock();
		}

		//task也是一个线程，显示的线程
		DisplayBitmapTask displayBitmapTask = new DisplayBitmapTask(bmp, imageLoadingInfo, engine, loadedFrom);
		runTask(displayBitmapTask, syncLoading, handler, engine);
	}

	5）下面先看下如何获取bitmap的：
	private Bitmap tryLoadBitmap() throws TaskCancelledException {
		Bitmap bitmap = null;
		try {
			//从本地磁盘中获取配置图片
			File imageFile = configuration.diskCache.get(uri);
			if (imageFile != null && imageFile.exists()) {
				L.d(LOG_LOAD_IMAGE_FROM_DISK_CACHE, memoryCacheKey);
				loadedFrom = LoadedFrom.DISC_CACHE;

				checkTaskNotActual();
				//如果有，直接生成bitmap
				bitmap = decodeImage(Scheme.FILE.wrap(imageFile.getAbsolutePath()));
			}
			if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
				L.d(LOG_LOAD_IMAGE_FROM_NETWORK, memoryCacheKey);
				loadedFrom = LoadedFrom.NETWORK;

				String imageUriForDecoding = uri;
				if (options.isCacheOnDisk() && tryCacheImageOnDisk()) {
					imageFile = configuration.diskCache.get(uri);
					if (imageFile != null) {
						imageUriForDecoding = Scheme.FILE.wrap(imageFile.getAbsolutePath());
					}
				}

				checkTaskNotActual();
				bitmap = decodeImage(imageUriForDecoding);

				if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
					fireFailEvent(FailType.DECODING_ERROR, null);
				}
			}
		} catch (IllegalStateException e) {
			fireFailEvent(FailType.NETWORK_DENIED, null);
		} catch (TaskCancelledException e) {
			throw e;
		} catch (IOException e) {
			L.e(e);
			fireFailEvent(FailType.IO_ERROR, e);
		} catch (OutOfMemoryError e) {
			L.e(e);
			fireFailEvent(FailType.OUT_OF_MEMORY, e);
		} catch (Throwable e) {
			L.e(e);
			fireFailEvent(FailType.UNKNOWN, e);
		}
		return bitmap;
	}

	最终调用了ImageDecoder的decode（）方法生成了需要的bitmap
	@Override
	public Bitmap decode(ImageDecodingInfo decodingInfo) throws IOException {
		Bitmap decodedBitmap;
		ImageFileInfo imageInfo;

		InputStream imageStream = getImageStream(decodingInfo);
		try {
			imageInfo = defineImageSizeAndRotation(imageStream, decodingInfo);
			imageStream = resetStream(imageStream, decodingInfo);
			Options decodingOptions = prepareDecodingOptions(imageInfo.imageSize, decodingInfo);
			decodedBitmap = BitmapFactory.decodeStream(imageStream, null, decodingOptions);
		} finally {
			IoUtils.closeSilently(imageStream);
		}

		if (decodedBitmap == null) {
			L.e(ERROR_CANT_DECODE_IMAGE, decodingInfo.getImageKey());
		} else {
			decodedBitmap = considerExactScaleAndOrientatiton(decodedBitmap, decodingInfo, imageInfo.exif.rotation,
					imageInfo.exif.flipHorizontal);
		}
		return decodedBitmap;
	}


	6）我们接下来看下生成bitmap了，然后干了什么

	DisplayBitmapTask displayBitmapTask = new DisplayBitmapTask(bmp, imageLoadingInfo, engine, loadedFrom);
	runTask(displayBitmapTask, syncLoading, handler, engine);

	构造了一个DisplayBitmapTask这个类，然后执行runTask方法

	static void runTask(Runnable r, boolean sync, Handler handler, ImageLoaderEngine engine) {
		if (sync) {
			r.run();
		} else if (handler == null) {
			engine.fireCallback(r);
		} else {
			handler.post(r);
		}
	}

	7）这里多了个判断handler是否为空，这个handler最初始位置是哪里呢，我们来回找下
	public LoadAndDisplayImageTask(ImageLoaderEngine engine, ImageLoadingInfo imageLoadingInfo, Handler handler)
	构造LoadAndDisplayImageTask这个类的时候传进来的

	private static Handler defineHandler(DisplayImageOptions options) {
		Handler handler = options.getHandler();
		if (options.isSyncLoading()) {
			handler = null;
		} else if (handler == null && Looper.myLooper() == Looper.getMainLooper()) {
			handler = new Handler();
		}
		return handler;
	}
	handler不为空，所有执行了post

	8）DisplayBitmapTask实现了Runnable这个类，所有我们看下这个里面的实现run方法
	@Override
	public void run() {
		if (imageAware.isCollected()) {
			L.d(LOG_TASK_CANCELLED_IMAGEAWARE_COLLECTED, memoryCacheKey);
			listener.onLoadingCancelled(imageUri, imageAware.getWrappedView());
		} else if (isViewWasReused()) {
			L.d(LOG_TASK_CANCELLED_IMAGEAWARE_REUSED, memoryCacheKey);
			listener.onLoadingCancelled(imageUri, imageAware.getWrappedView());
		} else {
			L.d(LOG_DISPLAY_IMAGE_IN_IMAGEAWARE, loadedFrom, memoryCacheKey);
			displayer.display(bitmap, imageAware, loadedFrom);
			engine.cancelDisplayTaskFor(imageAware);
			listener.onLoadingComplete(imageUri, imageAware.getWrappedView(), bitmap);
		}
	}
	
	9）从这可以看出来，最终执行了displayer.display(bitmap, imageAware, loadedFrom);显示到view上

	10）这里会遇到一个问题，就是如果调用获取bitmap的方法
	ImageLoader.getInstance().loadImage(url, options, imageLoadingListener);
	如果时间间隔很小的情况下调用两个相同的url的话，一个会正常显示，另一个会cancel掉

	我们可以看上面的run方法里
	第一个先执行的会cancelDisplay（）方法，这个方法会删除map里缓存的imageAware和url，第二个后执行的会找不到url，所以isViewWasReused返回true
	不执行下面display了


2、Glide源码研究

	1）使用方法
	Glide.with(this).load(ivGlideUrl).into(ivGlide);
	最简单的使用方法
	这个不用初始化，这个是比imageloader稍微好点的

	2）





