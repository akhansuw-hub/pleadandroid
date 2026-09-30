import Foundation
struct Color: Equatable {
    var hex: UInt32; var alpha: Double = 1
    init(hex: UInt32) { self.hex = hex }
    func opacity(_ a: Double) -> Color { var c = self; c.alpha = a; return c }
}
struct Avatar {
    enum Hairstyle: String, CaseIterable { case buzz, short, long, curly, bun, ponytail }
    enum Outfit: String, CaseIterable { case tee, hoodie, shirt, dress, suit }
    var skin: Int; var hair: Int; var hairstyle: Hairstyle; var top: Int; var outfit: Outfit
    public static let skinTones: [UInt32]  = [0xFFE0C8, 0xF5C9A6, 0xD9A377, 0xB8784E, 0x8D5533, 0x5C3A21]
    public static let hairColours: [UInt32] = [0x1E1410, 0x4A2C1B, 0x8A4B2A, 0xC98A4B, 0xE9D5A1, 0x8A2C2A]
    public static let topColours: [UInt32]  = [0x8A2C2A, 0x764534, 0xCA7356, 0xEB9996, 0xC99558, 0x2F5D62, 0x3B4A7A, 0x2A1310]
}
enum PixelAvatar {
    static let side = 16

    /// Palette entry indices used in the grid.
    enum Px: Equatable { case none, skin, hair, top, topDark, eye, mouth, collar, tie, blush }

    /// Row-major 16×16 grid for the avatar.
    static func grid(for a: Avatar) -> [[Color?]] {
        var g = [[Px]](repeating: [Px](repeating: .none, count: side), count: side)
        func set(_ r: Int, _ cs: ClosedRange<Int>, _ p: Px) { guard (0..<side).contains(r) else { return }; for c in cs where (0..<side).contains(c) { g[r][c] = p } }
        func put(_ r: Int, _ c: Int, _ p: Px) { if (0..<side).contains(r), (0..<side).contains(c) { g[r][c] = p } }

        // Body / outfit (rows 11–15)
        switch a.outfit {
        case .tee:
            set(11, 5...10, .top); set(12, 4...11, .top); set(13, 4...11, .top); set(14, 4...11, .top); set(15, 4...11, .top)
        case .hoodie:
            set(11, 4...11, .top); set(12, 3...12, .top); set(13, 3...12, .top); set(14, 3...12, .top); set(15, 3...12, .top)
            set(11, 6...9, .topDark) // hood opening
            set(14, 6...9, .topDark) // pocket
        case .shirt:
            set(11, 5...10, .top); set(12, 4...11, .top); set(13, 4...11, .top); set(14, 4...11, .top); set(15, 4...11, .top)
            put(11, 6, .collar); put(11, 9, .collar); put(12, 7, .collar); put(12, 8, .collar)
            put(13, 8, .topDark); put(14, 8, .topDark) // button line
        case .dress:
            set(11, 5...10, .top); set(12, 5...10, .top); set(13, 4...11, .top); set(14, 3...12, .top); set(15, 2...13, .top)
            put(12, 7, .topDark); put(12, 8, .topDark)
        case .suit:
            set(11, 4...11, .topDark); set(12, 3...12, .topDark); set(13, 3...12, .topDark); set(14, 3...12, .topDark); set(15, 3...12, .topDark)
            put(11, 7, .collar); put(11, 8, .collar); put(12, 7, .collar); put(12, 8, .collar)
            put(13, 7, .tie); put(13, 8, .tie); put(14, 7, .tie); put(14, 8, .tie)
        }
        // Neck
        set(10, 7...8, .skin)
        // Head (rows 3–9, cols 4–11)
        for r in 3...9 { set(r, 4...11, .skin) }
        set(3, 5...10, .skin); put(3, 4, .none); put(3, 11, .none)
        set(9, 5...10, .skin); put(9, 4, .none); put(9, 11, .none)
        // Face
        put(6, 6, .eye); put(6, 9, .eye)
        set(8, 7...8, .mouth)
        put(7, 5, .blush); put(7, 10, .blush)
        // Hair
        switch a.hairstyle {
        case .buzz:
            set(2, 5...10, .hair); set(3, 4...11, .hair)
        case .short:
            set(1, 5...10, .hair); set(2, 4...11, .hair); set(3, 4...11, .hair); set(4, 4...5, .hair); set(4, 10...11, .hair); put(5, 4, .hair); put(5, 11, .hair)
        case .long:
            set(1, 5...10, .hair); set(2, 4...11, .hair); set(3, 4...11, .hair)
            for r in 4...11 { put(r, 3, .hair); put(r, 4, .hair); put(r, 11, .hair); put(r, 12, .hair) }
            set(12, 3...4, .hair); set(12, 11...12, .hair)
        case .curly:
            set(0, 6...9, .hair); set(1, 4...11, .hair); set(2, 3...12, .hair); set(3, 3...12, .hair)
            set(4, 3...4, .hair); set(4, 11...12, .hair); set(5, 3...4, .hair); set(5, 11...12, .hair); put(6, 3, .hair); put(6, 12, .hair)
        case .bun:
            set(0, 6...9, .hair); set(1, 5...10, .hair); set(2, 4...11, .hair); set(3, 4...11, .hair); put(4, 4, .hair); put(4, 11, .hair)
        case .ponytail:
            set(1, 5...10, .hair); set(2, 4...11, .hair); set(3, 4...11, .hair); put(4, 4, .hair); put(4, 11, .hair)
            for r in 4...10 { put(r, 12, .hair) }; put(11, 12, .hair); put(11, 13, .hair)
        }

        let skin = Color(hex: Avatar.skinTones[clamp(a.skin, Avatar.skinTones.count)])
        let hair = Color(hex: Avatar.hairColours[clamp(a.hair, Avatar.hairColours.count)])
        let topHex = Avatar.topColours[clamp(a.top, Avatar.topColours.count)]
        let top = Color(hex: topHex)
        let topDark = Color(hex: darken(topHex))
        return g.map { row in row.map { px -> Color? in
            switch px {
            case .none: nil
            case .skin: skin
            case .hair: hair
            case .top: top
            case .topDark: topDark
            case .eye: Color(hex: 0x2A1310)
            case .mouth: Color(hex: 0x8A2C2A)
            case .collar: Color(hex: 0xFFFDFC)
            case .tie: Color(hex: 0x8A2C2A)
            case .blush: Color(hex: 0xEB9996).opacity(0.7)
            }
        } }
    }

    static func clamp(_ i: Int, _ n: Int) -> Int { max(0, min(n - 1, i)) }
    static func darken(_ hex: UInt32) -> UInt32 {
        let r = UInt32(Double((hex >> 16) & 0xFF) * 0.65), g = UInt32(Double((hex >> 8) & 0xFF) * 0.65), b = UInt32(Double(hex & 0xFF) * 0.65)
        return (r << 16) | (g << 8) | b
    }
}

extension PixelAvatar {
    static func outline(of grid: [[Color?]]) -> [(Int, Int)] {
        var out: [(Int, Int)] = []
        let n = grid.count
        for r in 0..<n {
            for c in 0..<n where grid[r][c] == nil {
                func filled(_ rr: Int, _ cc: Int) -> Bool {
                    guard rr >= 0, rr < n, cc >= 0, cc < n else { return false }
                    return grid[rr][cc] != nil
                }
                if filled(r - 1, c) || filled(r + 1, c) || filled(r, c - 1) || filled(r, c + 1) { out.append((r, c)) }
            }
        }
        return out
    }
}
func tok(_ c: Color?) -> String {
    guard let c else { return "." }
    return String(format: "%06X%02X", c.hex, Int((c.alpha * 255).rounded()))
}
var combos: [(Int, Int, Int)] = [(2, 1, 0), (0, 0, 0), (5, 5, 7), (-1, 99, 3), (3, 4, 5), (1, 2, 6)]
for h in Avatar.Hairstyle.allCases { for o in Avatar.Outfit.allCases { for (s, hr, t) in combos {
    let a = Avatar(skin: s, hair: hr, hairstyle: h, top: t, outfit: o)
    let g = PixelAvatar.grid(for: a)
    print("# \(h.rawValue) \(o.rawValue) \(s) \(hr) \(t)")
    for row in g { print(row.map(tok).joined(separator: " ")) }
    print("O " + PixelAvatar.outline(of: g).map { "\($0.0),\($0.1)" }.joined(separator: " "))
}}}
