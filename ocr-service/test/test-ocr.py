from rapidocr import RapidOCR

# Initialize with your bundled models (same ones as app.py)
engine = RapidOCR(params={
    "Det.model_path": "../models/multi_PP-OCRv6_det_small.onnx",
    "Cls.model_path": "../models/ch_ppocr_mobile_v2.0_cls_infer.onnx",
    "Rec.model_path": "../models/latin_PP-OCRv5_rec_mobile.onnx",
})

image_path = "test_document_fr.png" # Put a sample image here
result = engine(image_path)

if result.txts:
    for text, score in zip(result.txts, result.scores):
        print(f"Text: {text} (Confidence: {score})")
else:
    print("No text detected.")
