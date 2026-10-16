namespace Haechi;

/// <summary>DNS 패킷에서 필요한 만큼만 읽고, 차단 응답을 만든다 (RFC 1035).</summary>
static class DnsMessage
{
    public const ushort TypeA = 1, TypeAAAA = 28;

    /// <summary>첫 질문의 이름/타입을 읽는다. questionEnd = 질문 섹션이 끝나는 위치.</summary>
    public static bool TryParseQuestion(ReadOnlySpan<byte> msg, out string name, out ushort qtype, out int questionEnd)
    {
        name = ""; qtype = 0; questionEnd = 0;
        if (msg.Length < 12) return false;
        if ((msg[2] & 0x80) != 0) return false;                 // 응답 패킷이면 무시
        if (((msg[4] << 8) | msg[5]) < 1) return false;         // QDCOUNT

        var sb = new System.Text.StringBuilder();
        int p = 12;
        while (true)
        {
            if (p >= msg.Length) return false;
            int len = msg[p++];
            if (len == 0) break;
            if (len > 63 || p + len > msg.Length) return false; // 질문에는 압축 포인터가 오지 않는다
            if (sb.Length > 0) sb.Append('.');
            for (int i = 0; i < len; i++) sb.Append((char)msg[p + i]);
            p += len;
        }
        if (p + 4 > msg.Length) return false;
        qtype = (ushort)((msg[p] << 8) | msg[p + 1]);
        questionEnd = p + 4;
        name = sb.ToString().ToLowerInvariant();
        return name.Length > 0;
    }

    /// <summary>A → 0.0.0.0, AAAA → ::, 그 밖의 타입 → 답 없음(NOERROR). 연결 시도가 바로 실패하게 만든다.</summary>
    public static byte[] BuildBlocked(ReadOnlySpan<byte> query, ushort qtype, int questionEnd)
    {
        int addrLen = qtype == TypeA ? 4 : qtype == TypeAAAA ? 16 : 0;
        int answerLen = addrLen > 0 ? 12 + addrLen : 0;
        var r = new byte[questionEnd + answerLen];
        query[..questionEnd].CopyTo(r);

        r[2] = (byte)(0x80 | (query[2] & 0x79));  // QR=1, OPCODE·RD 유지, AA·TC 끔
        r[3] = 0x80;                               // RA=1, RCODE=0
        r[4] = 0; r[5] = 1;                        // QDCOUNT=1
        r[6] = 0; r[7] = (byte)(addrLen > 0 ? 1 : 0);
        r[8] = r[9] = r[10] = r[11] = 0;           // NSCOUNT, ARCOUNT (EDNS 는 버린다)

        if (addrLen > 0)
        {
            int p = questionEnd;
            r[p++] = 0xC0; r[p++] = 0x0C;          // 이름 = 질문 이름을 가리키는 포인터
            r[p++] = 0; r[p++] = (byte)qtype;
            r[p++] = 0; r[p++] = 1;                // IN
            r[p++] = 0; r[p++] = 0; r[p++] = 0; r[p++] = 60;  // TTL 60초
            r[p++] = 0; r[p++] = (byte)addrLen;    // 주소는 모두 0
        }
        return r;
    }
}
