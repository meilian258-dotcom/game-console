use audio::{Audio, BUFFER_CAPACITY};

pub struct DefaultAudio {
	buffer_index: usize,
	buffer: [f32; BUFFER_CAPACITY],
	previous_value: f32
}

impl DefaultAudio {
	pub fn new() -> Self {
		DefaultAudio {
			buffer_index: 0,
			buffer: [0.0; BUFFER_CAPACITY],
			previous_value: 0.0
		}
	}
}

impl Audio for DefaultAudio {
	fn push(&mut self, value: f32) {
		if self.buffer_index >= BUFFER_CAPACITY {
			return;
		}
		self.buffer[self.buffer_index] = value;
		self.buffer_index += 1;
	}

	fn sample_count(&self) -> usize {
		self.buffer_index
	}

	fn copy_sample_buffer(&mut self, sample_buffer: &mut [f32]) {
		let copied = sample_buffer.len().min(self.buffer_index);
		for i in 0..copied {
			sample_buffer[i] = self.buffer[i];
			self.previous_value = sample_buffer[i];
		}
		for value in sample_buffer.iter_mut().skip(copied) {
			*value = self.previous_value;
		}
		self.buffer.copy_within(copied..self.buffer_index, 0);
		self.buffer_index -= copied;
	}
}
