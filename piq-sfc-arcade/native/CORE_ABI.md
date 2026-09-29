# PIQ jgenesis core ABI v1

ABI 版本：`1`

## 输入

玩家编号为 `0` 和 `1`。按键位从低到高依次为：

`B, Y, Select, Start, Up, Down, Left, Right, A, X, L, R`

ABI 使用紧凑的低 12 位，Rust 适配器负责转换为 jgenesis 的输入结构。

## 画面

- 像素格式：RGBA8888；
- 尺寸每帧可变，不能写死为 256x224；
- 最大合同尺寸：512x478；
- 同时返回像素宽高比和目标帧率；
- 帧缓冲只在下一次 `run_frame` 前有效，Java 端必须及时上传或复制。

## 声音

- 48,000 Hz；
- 交错双声道；
- 有符号 PCM16；
- 返回值使用“立体声采样帧数”，不是单个 `short` 的数量。

## 必需导出

```text
piq_sfc_abi_version
piq_sfc_create / piq_sfc_destroy
piq_sfc_load_rom
piq_sfc_set_input
piq_sfc_run_frame
piq_sfc_reset
piq_sfc_frame_width / height / stride / ptr / len
piq_sfc_pixel_aspect_ratio
piq_sfc_target_fps
piq_sfc_audio_ptr / sample_frames / sample_rate
piq_sfc_save_state_size / save_state / load_state
piq_sfc_sram_size / save_sram / load_sram
piq_sfc_last_error_ptr / last_error_len
piq_sfc_alloc / piq_sfc_free
```

无论后续选择 WASM 还是 JNI，Java 层只依赖这份语义合同，不依赖 jgenesis 的 Rust 类型。
