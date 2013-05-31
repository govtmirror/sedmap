// this version of each does not conflict with the jquery version
// TODO well actually jquery needs a push method on this for some reason - will look into it or use underscore later
// jquery is in the way on this cool extension
// Object.prototype.each = function(callback) {
/*
var each = function(obj, callback) {
    for (var key in this) {
    	if (this.hasOwnProperty(key)) {
	        callback(this[key],key)
	    }
    }
}
Array.prototype.each = function(callback) {
    for (var i=0; i<this.length; i++) {
        callback(this[i],i) 
    }
}
*/
Array.prototype.eachReverse = function(callback) {
    for (var i=this.length-1; i>=0; i--) {
        callback(this[i],i) 
    }
}
Array.prototype.remove = function(from, to) {
  var rest = this.slice((to || from) + 1 || this.length)
  this.length = from < 0 ? this.length + from : from
  return this.push.apply(this, rest)
}

var Tag = new function() {
	this.OPEN  = false
	this.CLOSE = true

	this.make = function(tag,close) {
		var clause = "<"+(close?'/':'')+tag+">"
		return clause
	}
	this.open = function(tag) {
		return this.make(tag, this.OPEN)
	}
	this.close = function(tag) {
		return this.make(tag, this.CLOSE)
	}
}

/*
<PropertyIsEqualTo>
<PropertyIsNotEqualTo>
<PropertyIsLessThan>
<PropertyIsLessThanOrEqualTo>
<PropertyIsGreaterThan>
<PropertyIsGreaterThanOrEqualTo>
*/
// And Or Not should be literal param clause keys



var Ogc = new function() {
	this.operators = {
		'=':'EqualTo',
		'!':'Not',
		'<':'LessThan',
		'>':'GreaterThan',
	}
	this.opcodes = Object.keys(this.operators)

	this.filter = function(params) {
		var filter = this.clause("Filter", params)
		return filter
	}

	
	this.clause = function(tag, params) {
		tag = this.propertyTag(tag)
		var clause = Tag.make(tag)
		clause += this.inner(params)
		clause += Tag.make(tag, Tag.CLOSE)
		return clause
	}
	
	this.propertyTag = function(tag) {
		if (tag.length > 2) return tag
		var newTag = "PropertyIs"
		var or = ""
		for (var oc=0; oc<tag.length; oc++) {
			var opcode = tag[oc]
			if (this.opcodes.indexOf(opcode) < 0) {
				return tag
			}
			newTag += or + this.operators[opcode]
			or = opcode==="!" ?"" :"Or" // when joining 'not' does not use 'Or'
		}
		return newTag
	}
	
	this.inner = function(params) {
		var clause = ""
		var _this = this
			
		if (params.name && params.value) {
			clause += this.property(params.name, params.value)
		} else {
			$.each(params, function(p,child) {
				if ( isNaN(Number(p)) ) {
					clause += _this.clause(p, child)
				} else {
					clause += _this.inner(child)
				}
			})
		}
		return clause
	}


	this.property = function(name, value) {
		var property  = this.wrap("PropertyName",name)
			property += this.wrap("Literal",value)
		return property
	}
	
	
	this.wrap = function(tag, value) {
		var wrap = Tag.open(tag)+value+Tag.close(tag)
		return wrap
	}

}

var mp = function(op, name, value) {
	param = {}
	param[op] = {name:name,value:value}
	return param
}


var stateFilter    = {Or:[]}
var basinFilter    = undefined
var drainageFilter = undefined

var getStateValues = function(el) {
    var st     = $(el).val()
    var state  = $(el).find('option:selected').text()
    return [st,state]
}

var removeStateFilter = function(state) {
	var indexes = []
    $.each(stateFilter.Or, function(index,filter){
        var key = Object.keys(filter)[0]
        var val = filter[key].value 
        if (val === state[0] || val === state[1]) {
        	indexes.push(index)
        }
    })
    // must be reversed because deleted early indexes will effect subsequent
    indexes.eachReverse(function(i){ 
    	stateFilter.Or.remove(i)
    })
}

var destroyStateFilter = function(self) {
	var state = getStateValues( $(self).parent().find('select') )
    removeStateFilter(state)
    $(self).parent().remove()
}

var addStateFilter = function(e) {
	var filter = {And:[]}
	var el     = e.srcElement
    var state  = getStateValues(el)

	$(el).data('oldState',state)
    
    // TODO generalize with the element id or attr
	stateFilter.Or.push( mp('=','STATE',state[0]) )
	stateFilter.Or.push( mp('=','STATE',state[1]) )
}

var getIdFromEvent = function(e) {
	return $(e.srcElement).attr('id')
}

var cloneStateFilter = function() {
	var next = $('#baseState').clone()
	next.attr('id','anotherState')
	$(next).find('select').attr('id','aState')
	next.append('<input type="button" value="-" class="destroy" onclick="destroyStateFilter(this)">')

    var state = getStateValues('#STATE')
	$(next).find('select').data('oldState', state)
	$(next).find('select').val([state[0]])
	$(next).change(onStateChange)
	// add the new state selection to the dom
	$('#states').prepend(next)
	// clear the original for then next state
	$('#STATE').val([''])
}

//store old value
var onStateFocus = function(e) {
	var el  = e.srcElement
    var val = $(el).val()
    var txt = $(el+' option:selected').text()
}
var onStateChange = function(e) {
	// first remove the old filter
	var el    = e.srcElement
	var state = $(el).data('oldState')
	removeStateFilter(state)
	// then add the new filter
	addStateFilter(e)
}

$().ready(function(){

	$('#applyFilter').click(applyFilter)
	$('input.basin').blur(onBasinBlur)
	$('input.drainage').blur(onDrainageBlur)

	$('#STATE').change(function(e){
		addStateFilter(e)
		cloneStateFilter()
	})

	$('#clearFilter').click( function(e) {
		$('#states').find('input.destroy').parent().remove()
		$('input.drainage').val('')
		$('input.basin').val('')
		
		stateFilter    = {Or:[]}
		basinFilter    = undefined
		drainageFilter = undefined
		applyFilterToLayers('','all')
	})
})

var applyFilterToLayers = function(ogcXml, applyTo) {
	if (applyTo === 'all') {
		applyTo = Object.keys(layers)
	}
	$.each(applyTo, function(i,layerName) {
		layers[layerName].mergeNewParams({FILTER:ogcXml})
	})
}

var applyFilter = function() {
	var filter = {And:[]}
	if (stateFilter.Or.length) {
		filter.And.push( stateFilter )
		var ogcXml = Ogc.filter(filter)

		applyFilterToLayers(ogcXml, ['States','Counties','NID'])
	}
	if (basinFilter || drainageFilter) {
		if (basinFilter) {
			filter.And.push(basinFilter)
		}
		if (drainageFilter) {
			filter.And.push(drainageFilter[0])
			filter.And.push(drainageFilter[1])
		}
	}
	if (filter.And.length) {
		var ogcXml = Ogc.filter(filter)
		applyFilterToLayers(ogcXml, ['Instant Sites','Daily Sites'])
	}	
	//layers['HUC8'].mergeNewParams({FILTER:ogcXml})
}


var applyDrainage = function(values) {
	drainageFilter = []
	drainageFilter.push( mp('>=','DRAINAGE_AREA_MI_SQ',values[0]) )
	drainageFilter.push( mp('<=','DRAINAGE_AREA_MI_SQ',values[1]) )
}

var onDrainageBlur = function() {
	var errorText = ""
	var vals = []
	$('input.drainage').each(function(i,input) {
		var val = $(input).val()
		console.log(val)
		if (val === "") return
		if (! $.isNumeric(val) || val<0 ) {
			errorText = 'Drainages must be positive numbers.'
			$(input).focus()
		}
		vals.push(val)
	})
	
	if (vals.length === 2) {
		if (vals[0]>vals[1]) {
			errorText = 'Initail drainages must be less than the second.'
		} else {
			applyDrainage(vals)
		}
	} 
	$('#drainage-warn').text(errorText)
}



var onBasinBlur = function(e) {
	var el  = e.srcElement
	var val = $(el).val()
	basinFilter = mp('=','BASIN',val)
}



var Test = new function() {
	this.equal = function(actual, expect, msg) {
		if (actual !== expect) {
			msg = msg ?msg : "actual value was not equal to expected: '" + expect
			alert(msg + "' but was '" + actual + "'")
			return false
		}
		return true
	}
}

// test building property comparisons
var tag    = "foo"
var expect = "foo"
var actual = Ogc.propertyTag(tag)
Test.equal(actual,expect)
var tag    = "="
var expect = "PropertyIsEqualTo"
var actual = Ogc.propertyTag(tag)
Test.equal(actual,expect)
var tag    = "!="
var expect = "PropertyIsNotEqualTo"
var actual = Ogc.propertyTag(tag)
Test.equal(actual,expect)
var tag    = ">="
var expect = "PropertyIsGreaterThanOrEqualTo"
var actual = Ogc.propertyTag(tag)
Test.equal(actual,expect)

// simple clause test
var params = {name:'foo',value:'bar'}
var expect = "<clause><PropertyName>foo</PropertyName><Literal>bar</Literal></clause>"
var actual = Ogc.clause('clause',params)
Test.equal(actual,expect)

// logical multiple operator clause test
var param1 = {name:'foo1',value:'bar1'}
var param2 = {name:'foo2',value:'bar2'}
var params = {Or:[{'=':param1},{'!=':param2}]}
var expect = "<clause><Or><PropertyIsEqualTo><PropertyName>foo1</PropertyName><Literal>bar1</Literal></PropertyIsEqualTo><PropertyIsNotEqualTo><PropertyName>foo2</PropertyName><Literal>bar2</Literal></PropertyIsNotEqualTo></Or></clause>"
var actual = Ogc.clause('clause',params)
Test.equal(actual,expect)

// complex logical operator filter test - note the test params do not have to make logical sense to test xml rendering
var param1 = {name:'foo1',value:'bar1'}
var param2 = {name:'foo2',value:'bar2'}
var params = [{Or:[{'>=':param1},{'!=':param2}]},{And:[{'=':param1},{'=':param2}]},{And:[{'=':param1},{'=':param2}]}]
var expect = "<Filter><Or><PropertyIsGreaterThanOrEqualTo><PropertyName>foo1</PropertyName><Literal>bar1</Literal></PropertyIsGreaterThanOrEqualTo><PropertyIsNotEqualTo><PropertyName>foo2</PropertyName><Literal>bar2</Literal></PropertyIsNotEqualTo></Or><And><PropertyIsEqualTo><PropertyName>foo1</PropertyName><Literal>bar1</Literal></PropertyIsEqualTo><PropertyIsEqualTo><PropertyName>foo2</PropertyName><Literal>bar2</Literal></PropertyIsEqualTo></And><And><PropertyIsEqualTo><PropertyName>foo1</PropertyName><Literal>bar1</Literal></PropertyIsEqualTo><PropertyIsEqualTo><PropertyName>foo2</PropertyName><Literal>bar2</Literal></PropertyIsEqualTo></And></Filter>"
var actual = Ogc.filter(params)
Test.equal(actual,expect)

// actual logical filter test
var param1 = {name:'STATE',value:'WI'}
var param2 = {name:'STATE',value:'Wisconsin'}
var params = {Or:[{'=':param1},{'=':param2}]}
var expect = "<Filter><Or><PropertyIsEqualTo><PropertyName>STATE</PropertyName><Literal>WI</Literal></PropertyIsEqualTo><PropertyIsEqualTo><PropertyName>STATE</PropertyName><Literal>Wisconsin</Literal></PropertyIsEqualTo></Or></Filter>"
var actual = Ogc.filter(params)
Test.equal(actual,expect)






















