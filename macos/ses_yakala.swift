// MED LIVE TRANSLATE — macOS bilgisayar sesi yakalayıcı (ScreenCaptureKit, macOS 13+).
//
// Hoparlöre giden tüm sesi (bu programın kendi sesi hariç) 16 kHz mono float32 (little-endian)
// olarak stdout'a yazar. Python motoru (canli_altyazi/ses.py) bu süreci başlatıp akışı okur.
// stdout kapanınca (motor kapandı) ya da stdin kapanınca kendiliğinden çıkar.
// Hata durumunda stderr'e "HATA: ..." yazar; izin verilmediyse çıkış kodu 3.
//
// macOS bu sesi yalnızca "Ekran Kaydı ve Sistem Sesi Kaydı" izniyle verir (Sistem Ayarları →
// Gizlilik ve Güvenlik). Görüntü alınmaz: akış 2x2 piksel, saniyede 1 kare ile açılır ve kareler atılır.
import AVFoundation
import CoreMedia
import Foundation
import ScreenCaptureKit

let hedefOrnekleme = 16000.0

final class Yakalayici: NSObject, SCStreamOutput, SCStreamDelegate {
    private var akis: SCStream?
    private var donusturucu: AVAudioConverter?
    private var kaynakBicimi: AVAudioFormat?
    private let hedefBicim = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: hedefOrnekleme,
                                           channels: 1, interleaved: false)!
    private let kuyruk = DispatchQueue(label: "ses")

    func baslat() async throws {
        let icerik = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard let ekran = icerik.displays.first else { throw NSError(domain: "mlt", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "ekran bulunamadı"]) }
        // kendi sürecimizi (ve ebeveyn programı) dışarıda bırak: altyazı programı ses çıkarmaz ama yine de
        let suzgec = SCContentFilter(display: ekran, excludingApplications: [], exceptingWindows: [])
        let ayar = SCStreamConfiguration()
        ayar.capturesAudio = true
        ayar.excludesCurrentProcessAudio = true
        ayar.sampleRate = 48000
        ayar.channelCount = 2
        ayar.width = 2
        ayar.height = 2
        ayar.minimumFrameInterval = CMTime(value: 1, timescale: 1)
        ayar.queueDepth = 3
        let a = SCStream(filter: suzgec, configuration: ayar, delegate: self)
        try a.addStreamOutput(self, type: .audio, sampleHandlerQueue: kuyruk)
        try await a.startCapture()
        akis = a
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer tampon: CMSampleBuffer, of tur: SCStreamOutputType) {
        guard tur == .audio, tampon.isValid, let bicimAciklama = tampon.formatDescription,
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(bicimAciklama)?.pointee else { return }
        var asbdKopya = asbd
        guard let bicim = AVAudioFormat(streamDescription: &asbdKopya) else { return }
        if kaynakBicimi == nil || kaynakBicimi! != bicim {
            kaynakBicimi = bicim
            donusturucu = AVAudioConverter(from: bicim, to: hedefBicim)
        }
        let kare = AVAudioFrameCount(CMSampleBufferGetNumSamples(tampon))
        guard kare > 0, let giris = AVAudioPCMBuffer(pcmFormat: bicim, frameCapacity: kare) else { return }
        giris.frameLength = kare
        let durum = CMSampleBufferCopyPCMDataIntoAudioBufferList(tampon, at: 0, frameCount: Int32(kare),
                                                                 into: giris.mutableAudioBufferList)
        guard durum == noErr, let d = donusturucu else { return }
        let kapasite = AVAudioFrameCount(Double(kare) * hedefOrnekleme / bicim.sampleRate) + 32
        guard let cikis = AVAudioPCMBuffer(pcmFormat: hedefBicim, frameCapacity: kapasite) else { return }
        var verildi = false
        var hata: NSError?
        d.convert(to: cikis, error: &hata) { _, girisDurumu in
            if verildi { girisDurumu.pointee = .noDataNow; return nil }
            verildi = true
            girisDurumu.pointee = .haveData
            return giris
        }
        guard hata == nil, cikis.frameLength > 0, let veri = cikis.floatChannelData else { return }
        let bayt = Data(bytes: veri[0], count: Int(cikis.frameLength) * MemoryLayout<Float>.size)
        do {
            try FileHandle.standardOutput.write(contentsOf: bayt)
        } catch {
            exit(0)  // motor kapandı (boru kırıldı)
        }
    }

    func stream(_ stream: SCStream, didStopWithError hata: Error) {
        FileHandle.standardError.write("HATA: akış durdu: \(hata.localizedDescription)\n".data(using: .utf8)!)
        exit(2)
    }
}

signal(SIGPIPE, SIG_IGN)
// stdin kapanınca (motor öldü) çık
Thread.detachNewThread {
    while FileHandle.standardInput.availableData.count > 0 {}
    exit(0)
}
let yakalayici = Yakalayici()
Task {
    do {
        try await yakalayici.baslat()
        FileHandle.standardError.write("HAZIR\n".data(using: .utf8)!)
    } catch {
        let ns = error as NSError
        // SCStreamErrorUserDeclined = -3801: kullanıcı izin vermedi
        let izinYok = ns.domain == SCStreamErrorDomain && ns.code == -3801
        FileHandle.standardError.write("HATA: \(izinYok ? "IZIN_YOK" : error.localizedDescription)\n".data(using: .utf8)!)
        exit(izinYok ? 3 : 1)
    }
}
RunLoop.main.run()
