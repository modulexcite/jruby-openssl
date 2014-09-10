/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006, 2007 Ola Bini <ola@ologix.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.ext.openssl;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.StringWriter;

import java.security.InvalidKeyException;
import java.security.PublicKey;
import java.security.PrivateKey;

import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x500.X500Name;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.ext.openssl.x509store.PEMInputOutput;
import org.jruby.runtime.Arity;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.Visibility;

import org.jruby.ext.openssl.impl.PKCS10Request;
import static org.jruby.ext.openssl.OpenSSL.*;
import static org.jruby.ext.openssl.PKey._PKey;
import static org.jruby.ext.openssl.X509._X509;

/**
 * @author <a href="mailto:ola.bini@ki.se">Ola Bini</a>
 */
public class Request extends RubyObject {
    private static final long serialVersionUID = -2886532636278901502L;

    private static ObjectAllocator REQUEST_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new Request(runtime, klass);
        }
    };

    public static void createRequest(final Ruby runtime, final RubyModule _X509) {
        RubyClass _Request = _X509.defineClassUnder("Request", runtime.getObject(), REQUEST_ALLOCATOR);
        RubyClass _OpenSSLError = runtime.getModule("OpenSSL").getClass("OpenSSLError");
        _X509.defineClassUnder("RequestError", _OpenSSLError, _OpenSSLError.getAllocator());
        _Request.defineAnnotatedMethods(Request.class);
    }

    private IRubyObject subject;
    private PKey public_key;

    private final List<Attribute> attributes;

    private transient PKCS10Request request;

    public Request(Ruby runtime, RubyClass type) {
        super(runtime, type);
        attributes = new ArrayList<Attribute>();
    }

    @JRubyMethod(name = "initialize", rest = true, visibility = Visibility.PRIVATE)
    public IRubyObject initialize(final ThreadContext context, final IRubyObject[] args) {
        final Ruby runtime = context.runtime;

        if ( Arity.checkArgumentCount(runtime, args, 0, 1) == 0 ) return this;

        try {
            request = new PKCS10Request( StringHelper.readX509PEM(context, args[0]) );
        }
        catch (RuntimeException e) {
            throw newRequestError(runtime, "invalid certificate request data", e);
        }

        final String algorithm;
        final byte[] encoded;
        try {
            PublicKey pkey = request.getPublicKey();
            algorithm = pkey.getAlgorithm();
            encoded = pkey.getEncoded();
        }
        catch (IOException e) {
            throw newRequestError(runtime, e.getMessage());
        }

        final RubyString enc = RubyString.newString(runtime, encoded);
        if ( "RSA".equalsIgnoreCase(algorithm) ) {
            this.public_key = newPKeyImplInstance(context, "RSA", enc);
        }
        else if ( "DSA".equalsIgnoreCase(algorithm) ) {
            this.public_key = newPKeyImplInstance(context, "DSA", enc);
        }
        else {
            throw runtime.newLoadError("not implemented algo for public key: " + algorithm);
        }

        this.subject = newName( context, request.getSubject() );

        try { // final RubyModule _ASN1 = _ASN1(runtime);
            for ( org.bouncycastle.asn1.pkcs.Attribute attr : request.getAttributes() ) {
                final ASN1ObjectIdentifier type = attr.getAttrType();
                final ASN1Set values = attr.getAttrValues();
                attributes.add( newAttribute( context, type, values ) );
            }
        }
        catch (IOException e) {
            throw newRequestError(runtime, e.getMessage());
        }

        return this;
    }

    private static PKey newPKeyImplInstance(final ThreadContext context,
        final String className, final RubyString encoded) { // OpenSSL::PKey::RSA.new(encoded)
        return (PKey) _PKey(context.runtime).getClass(className).callMethod(context, "new", encoded);
    }

    private static Attribute newAttribute(final ThreadContext context,
        final ASN1ObjectIdentifier type, final ASN1Set values) throws IOException {
        return Attribute.newAttribute(context.runtime, type, values);
    }

    private static IRubyObject newName(final ThreadContext context, X500Name name) {
        if ( name == null ) return context.nil;
        return X509Name.newName(context.runtime, name);
    }

    @Override
    @JRubyMethod(visibility = Visibility.PRIVATE)
    public IRubyObject initialize_copy(IRubyObject obj) {
        final Ruby runtime = getRuntime();
        warn(runtime.getCurrentContext(), "WARNING: unimplemented method called: request#initialize_copy");

        if ( this == obj ) return this;

        checkFrozen();
        // subject = public_key = null;
        return this;
    }

    private PKCS10Request getRequest(boolean forceNew) {
        if ( ! forceNew && request != null ) return request;

        PublicKey publicKey = null;
        if ( public_key != null && ! public_key.isNil() ) {
            publicKey = public_key.getPublicKey();
        }
        final ThreadContext context = getRuntime().getCurrentContext();
        return request = new PKCS10Request( getX500Name(subject), publicKey, newAttributesImpl(context) );
    }

    private static X500Name getX500Name(final IRubyObject name) {
        return ((X509Name) name).getX500Name();
    }

    @JRubyMethod(name={"to_pem","to_s"})
    public IRubyObject to_pem() {
        StringWriter writer = new StringWriter();
        try {
            PEMInputOutput.writeX509Request(writer, getRequest(false));
            return getRuntime().newString( writer.toString() );
        }
        catch (IOException e) {
            throw Utils.newIOError(getRuntime(), e);
        }
    }

    @JRubyMethod
    public IRubyObject to_der() {
        try {
            ASN1Sequence seq = getRequest(false).toASN1Structure();
            return StringHelper.newString(getRuntime(), seq.getEncoded());
        }
        catch (IOException ex) {
            throw getRuntime().newIOErrorFromException(ex);
        }
    }

    @JRubyMethod
    public IRubyObject to_text(final ThreadContext context) {
        warn(context, "WARNING: unimplemented method called: request#to_text");
        return context.runtime.getNil();
    }

    @JRubyMethod
   public IRubyObject version() {
        return getRuntime().newFixnum( getRequest(false).getVersion() );
    }

    @JRubyMethod(name="version=")
    public IRubyObject set_version(final ThreadContext context, IRubyObject val) {
        // NOTE: This is meaningless, it doesn't do anything...
        // warn(context, "WARNING: meaningless method called: request#version=");
        return context.runtime.getNil();
    }

    @JRubyMethod
    public IRubyObject subject() {
        return subject == null ? subject = getRuntime().getNil() : subject;
    }

    @JRubyMethod(name="subject=")
    public IRubyObject set_subject(final IRubyObject val) {
        if ( val != this.subject ) {
            if (request != null) {
                request.setSubject( getX500Name(val) );
            }
            this.subject = val;
        }
        return val;
    }

    @JRubyMethod
    public IRubyObject signature_algorithm(final ThreadContext context) {
        warn(context, "WARNING: unimplemented method called: request#signature_algorithm");
        return context.runtime.getNil();
    }

    @JRubyMethod
    public IRubyObject public_key() {
        return public_key == null ? getRuntime().getNil() : public_key;
    }

    @JRubyMethod(name="public_key=")
    public IRubyObject set_public_key(final IRubyObject pkey) {
        if ( pkey != this.public_key ) {
            if (request != null) {
                request.setPublicKey( ((PKey) pkey).getPublicKey() );
            }
            this.public_key = (PKey) pkey;
        }
        return pkey;
    }

    @JRubyMethod
    public IRubyObject sign(final ThreadContext context,
        final IRubyObject key, final IRubyObject digest) {

        PublicKey publicKey = public_key.getPublicKey();
        PrivateKey privateKey = ((PKey) key).getPrivateKey();

        final String keyAlg = publicKey.getAlgorithm();
        final String digAlg = ((Digest) digest).getShortAlgorithm();
        final String digName = ((Digest) digest).name().toString();

        if ( PKCS10Request.algorithmMismatch(keyAlg, digAlg, digName) ) {
            throw newRequestError(context.runtime, null);
        }

        try {
            getRequest(true).sign(privateKey, digAlg);
        }
        catch (IOException e) {
            throw Utils.newIOError(context.runtime, e);
        }

        return this;
    }

    private List<org.bouncycastle.asn1.pkcs.Attribute> newAttributesImpl(final ThreadContext context) {
        ArrayList<org.bouncycastle.asn1.pkcs.Attribute> attrs = new ArrayList<org.bouncycastle.asn1.pkcs.Attribute>(attributes.size());
        for ( Attribute attribute : attributes ) {
            attrs.add( newAttributeImpl(context, attribute) );
        }
        return attrs;
    }

    private org.bouncycastle.asn1.pkcs.Attribute newAttributeImpl(final ThreadContext context,
        final Attribute attribute) {
        return org.bouncycastle.asn1.pkcs.Attribute.getInstance( attribute.toASN1( context ) );
    }

    @JRubyMethod
    public IRubyObject verify(final ThreadContext context, IRubyObject key) {
        final Ruby runtime = context.runtime; final PublicKey publicKey;
        try {
            publicKey = ( (PKey) key.callMethod(context, "public_key") ).getPublicKey();
            return runtime.newBoolean( getRequest(false).verify(publicKey) );
        }
        catch (InvalidKeyException e) {
            debugStackTrace(runtime, e);
            throw newRequestError(runtime, e.getMessage());
        }
        catch (IOException e) {
            debug(runtime, "Request#verify() failed:", e);
            return runtime.getFalse();
        }
        catch (RuntimeException e) {
            debug(runtime, "Request#verify() failed:", e);
            return runtime.getFalse();
        }
    }

    @JRubyMethod
    public IRubyObject attributes() {
        @SuppressWarnings("unchecked")
        List<IRubyObject> attributes = (List) this.attributes;
        return getRuntime().newArray(attributes);
    }

    @JRubyMethod(name="attributes=")
    public IRubyObject set_attributes(final ThreadContext context,final IRubyObject attributes) {
        this.attributes.clear();
        final RubyArray attrs = (RubyArray) attributes;
        for ( int i = 0; i < attrs.size(); i++ ) {
            this.attributes.add( (Attribute) attrs.entry(i) );
        }
        if (request != null) {
            request.setAttributes( newAttributesImpl(context) );
        }
        return attributes;
    }

    @JRubyMethod
    public IRubyObject add_attribute(final ThreadContext context,final IRubyObject attribute) {
        attributes.add( (Attribute) attribute );
        if (request != null) {
            request.addAttribute( newAttributeImpl( context, (Attribute) attribute ) );
        }
        return attribute;
    }

    private static RaiseException newRequestError(Ruby runtime, String message) {
        return Utils.newError(runtime, _X509(runtime).getClass("RequestError"), message);
    }

    private static RaiseException newRequestError(Ruby runtime, String message, Exception cause) {
        return Utils.newError(runtime, _X509(runtime).getClass("RequestError"), message, cause);
    }

}// Request
