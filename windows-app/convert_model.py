"""Export the same pretrained v0.5 model used by Android and check equivalence."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import urllib.request
import zipfile
import numpy as np
import tensorflow as tf
import onnxruntime as ort

root=Path(__file__).resolve().parent
work=root/'build/model'; work.mkdir(parents=True,exist_ok=True)
archive=work/'nn.zip'
urllib.request.urlretrieve('https://github.com/linrock/chessboard-recognizer/releases/download/v0.5/nn.zip',archive)
with zipfile.ZipFile(archive) as z:
    for item in z.infolist():
        target=(work/item.filename).resolve()
        if not target.is_relative_to(work.resolve()):
            raise ValueError('Unsafe archive entry')
    z.extractall(work)
saved=next(work.rglob('model.tf'))
model_dir=root/'models'; model_dir.mkdir(exist_ok=True)
output=model_dir/'pieces.onnx'
subprocess.run([sys.executable,'-m','tf2onnx.convert','--saved-model',str(saved),'--opset','13','--output',str(output)],check=True)
original=tf.saved_model.load(str(saved)).signatures['serving_default']
input_name=next(iter(original.structured_input_signature[1]))
session=ort.InferenceSession(str(output),providers=['CPUExecutionProvider'])
maximum=0.
for data in [np.zeros((1,32,32,1),np.float32),np.ones((1,32,32,1),np.float32),np.random.default_rng(22).random((1,32,32,1),dtype=np.float32)]:
    expected=next(iter(original(**{input_name:tf.constant(data)}).values())).numpy()
    actual=session.run(None,{session.get_inputs()[0].name:data})[0]
    assert actual.shape==(1,13)
    np.testing.assert_allclose(actual,expected,rtol=.001,atol=.0001)
    maximum=max(maximum,float(np.max(np.abs(actual-expected))))
(model_dir/'provenance.json').write_text(json.dumps({'source':'linrock/chessboard-recognizer v0.5','archive_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'onnx_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'maximum_test_difference':maximum},indent=2))
licenses=root/'licenses'; licenses.mkdir(exist_ok=True)
urllib.request.urlretrieve('https://raw.githubusercontent.com/linrock/chessboard-recognizer/master/LICENSE',licenses/'recognizer-MIT.txt')
print('Model conversion verified:',maximum)
