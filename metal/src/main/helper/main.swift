// Cadence Helper (Cadence: Apple Hardware): the jobs that need Apple frameworks the game's process can't use for them.
//
//   cadence-helper train-style STYLE_IMAGE CONTENT_DIR ITERATIONS OUT.mlmodel
//       A style transfer model trained with Create ML on this Mac (no download: Create ML's own feature extractor), for Style
//       Filters, which run it on the Neural Engine. Create ML's table (a row per iteration) while training, "done" at the end.
//   cadence-helper listen OUT_FILE [--locale ID] [--file AUDIO]
//       Voice Control: speech recognised on this Mac (Speech, on-device only: nothing is sent anywhere), each finished phrase
//       appended to OUT_FILE as a line. Runs as its own app (Cadence Helper.app), so macOS asks for the microphone and speech
//       recognition for it, not for Java. --file: recognise a recording instead (tests). Ends when OUT_FILE.stop appears.
import AVFoundation
import CreateML
import Foundation
import Speech

setvbuf(stdout, nil, _IOLBF, 0)
let args = CommandLine.arguments

func fail(_ message: String) -> Never {
	print("error: \(message)")
	exit(1)
}

func trainStyle() {
	guard args.count >= 6 else { fail("usage: train-style STYLE CONTENT_DIR ITERATIONS OUT") }
	let style = URL(fileURLWithPath: args[2]), content = URL(fileURLWithPath: args[3], isDirectory: true), out = URL(fileURLWithPath: args[5])
	var p = MLStyleTransfer.ModelParameters()
	p.maxIterations = Int(args[4]) ?? 300
	do {
		// (Create ML prints its own table, a row per iteration: "| N | ...", which Cadence reads as the progress)
		let model = try MLStyleTransfer(trainingData: .images(styleImage: style, contentDirectory: content, processingOption: nil), parameters: p)
		try model.write(to: out)
		print("done")
	} catch {
		fail("\(error)")
	}
}

final class Listener {
	let out: URL
	let recognizer: SFSpeechRecognizer
	let engine = AVAudioEngine()
	var request: SFSpeechAudioBufferRecognitionRequest?
	var task: SFSpeechRecognitionTask?
	var started = Date()

	init(out: URL, locale: String) {
		self.out = out
		guard let r = SFSpeechRecognizer(locale: Locale(identifier: locale)) else { fail("no speech recognizer for \(locale)") }
		self.recognizer = r
	}

	func write(_ line: String) {
		let data = (line + "\n").data(using: .utf8)!
		if let h = try? FileHandle(forWritingTo: out) {
			h.seekToEndOfFile()
			h.write(data)
			try? h.close()
		} else {
			try? data.write(to: out)
		}
	}

	func authorize() {
		let wait = DispatchSemaphore(value: 0)
		SFSpeechRecognizer.requestAuthorization { status in
			if status != .authorized { self.write("#denied speech recognition"); fail("speech recognition not allowed") }
			wait.signal()
		}
		wait.wait()
		if !recognizer.supportsOnDeviceRecognition { write("#no on-device recognition for this language") }
	}

	// One recognition request (Speech limits each to about a minute): restarted after each finished phrase or 50 s.
	func start() {
		task?.cancel()
		let req = SFSpeechAudioBufferRecognitionRequest()
		req.requiresOnDeviceRecognition = true
		req.shouldReportPartialResults = false
		request = req
		started = Date()
		task = recognizer.recognitionTask(with: req) { result, error in
			if let r = result, r.isFinal {
				let text = r.bestTranscription.formattedString.trimmingCharacters(in: .whitespaces)
				if !text.isEmpty { self.write(text) }
				DispatchQueue.main.async { self.start() }
			} else if error != nil {
				DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.start() }
			}
		}
	}

	func listenMicrophone() {
		let input = engine.inputNode
		let format = input.outputFormat(forBus: 0)
		input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in self.request?.append(buffer) }
		engine.prepare()
		do { try engine.start() } catch { fail("microphone: \(error)") }
		start()
		write("#listening")
		Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
			if FileManager.default.fileExists(atPath: self.out.path + ".stop") { exit(0) }
			// (a phrase that never finishes: ended so the next request starts within Speech's limit)
			if Date().timeIntervalSince(self.started) > 50 { self.request?.endAudio() }
		}
		RunLoop.main.run()
	}

	func listenFile(_ file: URL) {
		let req = SFSpeechURLRecognitionRequest(url: file)
		req.requiresOnDeviceRecognition = true
		let done = DispatchSemaphore(value: 0)
		task = recognizer.recognitionTask(with: req) { result, error in
			if let r = result, r.isFinal {
				self.write(r.bestTranscription.formattedString)
				done.signal()
			} else if let e = error {
				self.write("#error \(e.localizedDescription)")
				done.signal()
			}
		}
		while done.wait(timeout: .now() + 0.05) == .timedOut { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
	}
}

func listen() {
	guard args.count >= 3 else { fail("usage: listen OUT_FILE [--locale ID] [--file AUDIO]") }
	var locale = Locale.current.identifier, file: String?
	var i = 3
	while i < args.count {
		if args[i] == "--locale", i + 1 < args.count { locale = args[i + 1]; i += 2 }
		else if args[i] == "--file", i + 1 < args.count { file = args[i + 1]; i += 2 }
		else { i += 1 }
	}
	let l = Listener(out: URL(fileURLWithPath: args[2]), locale: locale)
	l.authorize()
	if let f = file { l.listenFile(URL(fileURLWithPath: f)) } else { l.listenMicrophone() }
}

switch args.count > 1 ? args[1] : "" {
case "train-style": trainStyle()
case "listen": listen()
default: fail("usage: cadence-helper train-style ... | listen ...")
}
