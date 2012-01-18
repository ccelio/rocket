package Top {

import Chisel._
import Node._;
import Constants._;

class ioDivider(width: Int) extends Bundle {
  // requests
  val div_val   = Bool(INPUT);
  val div_kill  = Bool(INPUT);
  val div_rdy   = Bool(OUTPUT);
  val dw        = UFix(1, INPUT);
  val div_fn    = UFix(2, INPUT);
  val div_waddr = UFix(5, INPUT);
  val dpath_rs1 = Bits(width, INPUT);
  val dpath_rs2 = Bits(width, INPUT);
  // responses
  val div_result_bits = Bits(width, OUTPUT);
  val div_result_tag  = UFix(5, OUTPUT);
  val div_result_val  = Bool(OUTPUT);
  val div_result_rdy  = Bool(INPUT);
}

// class ioDivider extends Bundle {
//   // requests
//   val req_val   = Bool(INPUT);
//   val req_rdy   = Bool(OUTPUT);
//   val req_fn    = UFix(3, INPUT);
//   val req_tag = UFix(5, INPUT);
//   val req_rs1   = Bits(64, INPUT);
//   val req_rs2   = Bits(64, INPUT);
//   // responses
//   val resp_val  = Bool(OUTPUT);
//   val resp_data = Bits(64, OUTPUT);
//   val resp_tag  = UFix(5, OUTPUT);
// }

class rocketDivider(width : Int) extends Component {
  val io = new ioDivider(width);
  
  val s_ready :: s_neg_inputs :: s_busy :: s_neg_outputs :: s_done :: Nil = Enum(5) { UFix() };
  val state = Reg(resetVal = s_ready);
  
  val count_bits  = java.math.BigInteger.valueOf(width).bitLength();
  val count       = Reg() { UFix() };
  val divby0      = Reg() { Bool() };
  val neg_quo     = Reg() { Bool() };
  val neg_rem     = Reg() { Bool() };
  val reg_waddr   = Reg() { UFix() };
  val rem         = Reg() { Bool() };
  val half        = Reg() { Bool() };
  
  val divisor     = Reg() { UFix() };
  val remainder   = Reg() { UFix() };
  val subtractor  = remainder(2*width, width).toUFix - divisor;
  
  val tc = (io.div_fn === DIV_D) || (io.div_fn === DIV_R);

  when (io.div_kill) {
    state <== s_ready;
  }
  
  // state machine
  switch (state) {
    is (s_ready) {
      when (!io.div_val)  { state <== s_ready; }
      when (tc)         { state <== s_neg_inputs };
      otherwise           { state <== s_busy; }
    }
    is (s_neg_inputs)     { state <== s_busy; }
    is (s_busy) {
      when (count != UFix(width))   { state <== s_busy; }
      when (!(neg_quo || neg_rem))  { state <== s_done; }
      otherwise                     { state <== s_neg_outputs; }
    }
    is (s_neg_outputs)              { state <== s_done; }
    is (s_done) {
      when (io.div_result_rdy)      { state <== s_ready; }
    }
  }

  // if we're doing 32-bit unsigned division, then we don't want the 32-bit
  // inputs to be sign-extended.
  val in_lhs = Mux(((io.dw === DW_32) && !tc), 
                   Cat(Fill(width/2, UFix(0,1)), io.dpath_rs1(width/2-1, 0)),
                   io.dpath_rs1).toUFix;

  val in_rhs = Mux(((io.dw === DW_32) && !tc), 
                   Cat(Fill(width/2, UFix(0,1)), io.dpath_rs2(width/2-1, 0)),
                   io.dpath_rs2).toUFix;                   
        
  when ((state === s_ready) && io.div_val) {
    count <== UFix(0, count_bits);
    half <== (io.dw === DW_32);
    neg_quo <== Bool(false);
    neg_rem <== Bool(false);
    rem <== (io.div_fn === DIV_R) || (io.div_fn === DIV_RU);
    reg_waddr <== io.div_waddr;
    divby0 <== Bool(true);
    divisor <== in_rhs;
    remainder <== Cat(Fill(width+1, UFix(0,1)), in_lhs).toUFix;
  }

  when (state === s_neg_inputs) {
    neg_rem <== remainder(width-1).toBool;
    neg_quo <== (remainder(width-1) != divisor(width-1));
    when (remainder(width-1).toBool) {
      remainder <== Cat(remainder(2*width, width), -remainder(width-1,0)).toUFix;
    }
    when (divisor(width-1).toBool) {
      divisor <== subtractor(width-1,0);
    }
  }
  when (state === s_neg_outputs) {
    when (neg_rem && neg_quo && !divby0) {
      remainder <== Cat(-remainder(2*width, width+1), remainder(width), -remainder(width-1,0)).toUFix;
    }
    when (neg_quo && !divby0) {
      remainder <== Cat(remainder(2*width, width), -remainder(width-1,0)).toUFix;
    }
    when (neg_rem) {
      remainder <== Cat(-remainder(2*width, width+1), remainder(width,0)).toUFix;
    }
    when (divisor(width-1).toBool) {
      divisor <== subtractor(width-1,0);
    }
  }
  when (state === s_busy) {
    count <== count + UFix(1);
    divby0 <== divby0 && !subtractor(width).toBool;
    remainder <== Mux(subtractor(width).toBool,
                      Cat(remainder(2*width-1, width), remainder(width-1,0), ~subtractor(width)),
                      Cat(subtractor(width-1, 0), remainder(width-1,0), ~subtractor(width))).toUFix;
  }  

  val result = Mux(rem, remainder(2*width, width+1), remainder(width-1,0));
  
  io.div_result_bits := Mux(half, Cat(Fill(width/2, result(width/2-1)), result(width/2-1,0)), result);
  io.div_rdy := (state === s_ready);
  io.div_result_tag := reg_waddr;
  io.div_result_val := (state === s_done);
}

}
