// Cadence Helper (the Apple hardware features): the jobs that need Apple frameworks the game's process can't use for them.
//
//   cadence-helper train-style STYLE_IMAGE CONTENT_DIR ITERATIONS OUT.mlmodel
//       A style transfer model trained with Create ML on this Mac (no download: Create ML's own feature extractor), for Style
//       Filters, which run it on the Neural Engine. Create ML's table (a row per iteration) while training, "done" at the end.
//   cadence-helper wait PID
//       Game Mode: what the game's first process becomes once it has started the game again as a new process (one macOS
//       treats as a game): it waits for that process, hands it the signals the launcher sends (quit, interrupt, hang up), and
//       exits with its exit code, so the launcher still sees one process from start to end.
//   cadence-helper listen OUT_FILE [--locale ID] [--parent PID] [--file AUDIO]
//       Voice Control: speech recognised on this Mac (Speech, on-device only: nothing is sent anywhere), each finished phrase
//       appended to OUT_FILE as a line. Runs as its own app (Cadence Helper.app), so macOS asks for the microphone and speech
//       recognition for it, not for Java. --file: recognise a recording instead (tests). Ends when OUT_FILE.stop appears or the
//       game (PID) is gone, and deletes both files then.
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
	let parent: pid_t
	let recognizer: SFSpeechRecognizer
	let engine = AVAudioEngine()
	let lock = NSLock()  // (the request: replaced on the main thread, appended to on the audio thread)
	var request: SFSpeechAudioBufferRecognitionRequest?
	var task: SFSpeechRecognitionTask?
	var started = Date()
	// the phrase being heard: its words so far and when they last changed (a pause of PAUSE seconds ends it)
	var heard = "", changed = Date()
	let pause = 0.8

	init(out: URL, locale: String, parent: pid_t) {
		self.out = out
		self.parent = parent
		guard let r = SFSpeechRecognizer(locale: Locale(identifier: locale)) else {
			try? "#no speech recognition for \(locale)\n".data(using: .utf8)!.write(to: out)
			fail("no speech recognizer for \(locale)")
		}
		self.recognizer = r
	}

	/** Stopped (the stop file, or the game gone): the files deleted, so nothing heard stays on disk. */
	func finish() -> Never {
		engine.stop()
		try? FileManager.default.removeItem(at: out)
		try? FileManager.default.removeItem(atPath: out.path + ".stop")
		exit(0)
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

	// One recognition request (Speech limits each to about a minute): restarted after each phrase or 50 s. Partial results: a
	// buffer request only finishes when its audio ends, so a phrase is taken as finished once its words stop changing for a pause.
	func start() {
		task?.cancel()
		let req = SFSpeechAudioBufferRecognitionRequest()
		req.requiresOnDeviceRecognition = true
		req.shouldReportPartialResults = true
		lock.lock()
		request = req
		lock.unlock()
		started = Date()
		heard = ""
		task = recognizer.recognitionTask(with: req) { result, error in
			DispatchQueue.main.async {
				guard self.request === req else { return }  // (an old request's last words)
				if let r = result {
					let text = r.bestTranscription.formattedString.trimmingCharacters(in: .whitespaces)
					if text != self.heard {
						self.heard = text
						self.changed = Date()
					}
					if r.isFinal { self.phraseDone() }
				} else if error != nil {
					DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.start() }
				}
			}
		}
	}

	func phraseDone() {
		if !heard.isEmpty { write(heard) }
		start()
	}

	func listenMicrophone() {
		let input = engine.inputNode
		let format = input.outputFormat(forBus: 0)
		if format.sampleRate == 0 || format.channelCount == 0 {
			write("#no microphone")
			fail("no microphone")
		}
		input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
			self.lock.lock()
			let r = self.request
			self.lock.unlock()
			r?.append(buffer)
		}
		engine.prepare()
		do { try engine.start() } catch {
			write("#microphone: \(error.localizedDescription)")
			fail("microphone: \(error)")
		}
		start()
		write("#listening")
		Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
			if FileManager.default.fileExists(atPath: self.out.path + ".stop") { self.finish() }
			if self.parent > 0 && kill(self.parent, 0) != 0 && errno == ESRCH { self.finish() }  // (the game quit or crashed)
			if !self.heard.isEmpty && Date().timeIntervalSince(self.changed) > self.pause {
				self.phraseDone()
			} else if Date().timeIntervalSince(self.started) > 50 {
				// (nothing finished within Speech's limit: a new request)
				self.phraseDone()
			}
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
	var locale = Locale.current.identifier, file: String?, parent: pid_t = 0
	var i = 3
	while i < args.count {
		if args[i] == "--locale", i + 1 < args.count { locale = args[i + 1]; i += 2 }
		else if args[i] == "--parent", i + 1 < args.count { parent = pid_t(args[i + 1]) ?? 0; i += 2 }
		else if args[i] == "--file", i + 1 < args.count { file = args[i + 1]; i += 2 }
		else { i += 1 }
	}
	let l = Listener(out: URL(fileURLWithPath: args[2]), locale: locale, parent: parent)
	l.authorize()
	if let f = file { l.listenFile(URL(fileURLWithPath: f)) } else { l.listenMicrophone() }
}

var signalSources: [DispatchSourceSignal] = []

func waitForGame() {
	guard args.count >= 3, let child = pid_t(args[2]) else { fail("usage: wait PID") }
	for s in [SIGTERM, SIGINT, SIGHUP, SIGQUIT] {
		signal(s, SIG_IGN)
		let source = DispatchSource.makeSignalSource(signal: s, queue: .main)
		source.setEventHandler { kill(child, s) }
		source.resume()
		signalSources.append(source)
	}
	DispatchQueue.global().async {
		var status: Int32 = 0
		var r: pid_t
		repeat { r = waitpid(child, &status, 0) } while r == -1 && errno == EINTR
		if r == -1 { exit(1) }
		let sig = status & 0x7f
		exit(sig == 0 ? (status >> 8) & 0xff : 128 + sig)
	}
	dispatchMain()
}

switch args.count > 1 ? args[1] : "" {
case "train-style": trainStyle()
case "listen": listen()
case "wait": waitForGame()
default: fail("usage: cadence-helper train-style ... | listen ... | wait PID")
}
